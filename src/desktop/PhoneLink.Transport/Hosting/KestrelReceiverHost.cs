using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Net.Http.Headers;
using PhoneLink.Core;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Models;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Transport.Http;

namespace PhoneLink.Transport.Hosting;

/// <summary>
/// 局域网 HTTPS Receiver（Kestrel）。Phase 1 暴露：
///   GET  /v1/health                预配对最小响应 / 带 token 完整响应
///   POST /v1/transfers             multipart 上传（metadata + file）
///   GET  /v1/transfers/{id}        上传状态确认
/// </summary>
public sealed class KestrelReceiverHost : IReceiverHost
{
    private readonly ReceiverOptions _options;
    private readonly IDeviceIdentityProvider _deviceIdentity;
    private readonly ITokenValidator _tokenValidator;
    private readonly ITransferService _transferService;
    private readonly ILoggerFactory _loggerFactory;
    private readonly ITlsCertificateProvider _certificateProvider;

    private WebApplication? _app;

    public KestrelReceiverHost(
        ReceiverOptions options,
        IDeviceIdentityProvider deviceIdentity,
        ITokenValidator tokenValidator,
        ITransferService transferService,
        ILoggerFactory loggerFactory,
        ITlsCertificateProvider certificateProvider)
    {
        _options = options;
        _deviceIdentity = deviceIdentity;
        _tokenValidator = tokenValidator;
        _transferService = transferService;
        _loggerFactory = loggerFactory;
        _certificateProvider = certificateProvider;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Services.AddSingleton(_options);
        builder.Services.AddSingleton(_deviceIdentity);
        builder.Services.AddSingleton(_tokenValidator);
        builder.Services.AddSingleton(_transferService);
        builder.Services.AddSingleton<ILoggerFactory>(_loggerFactory);

        var certificate = _certificateProvider.GetOrCreateCertificate();
        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            kestrel.Limits.MaxRequestBodySize = null;
            kestrel.Listen(IPAddress.Any, _options.Port, listen => listen.UseHttps(certificate));
        });

        var app = builder.Build();
        MapEndpoints(app);

        await app.StartAsync(cancellationToken);
        _app = app;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_app is null)
        {
            return;
        }

        await _app.StopAsync(cancellationToken);
        await _app.DisposeAsync();
        _app = null;
    }

    public async ValueTask DisposeAsync()
    {
        if (_app is not null)
        {
            await _app.DisposeAsync();
            _app = null;
        }
    }

    private void MapEndpoints(WebApplication app)
    {
        app.MapGet("/v1/health", async (HttpContext context, CancellationToken ct) =>
        {
            var validation = await TryAuthenticateAsync(context, ct);
            if (validation.IsValid)
            {
                var identity = await _deviceIdentity.GetIdentityAsync(ct);
                return Results.Json(new
                {
                    protocolVersion = AppInfo.ProtocolVersion,
                    deviceId = identity.DeviceId,
                    deviceName = identity.DisplayName,
                    status = "ok",
                });
            }

            return Results.Json(new { protocolVersion = AppInfo.ProtocolVersion });
        });

        app.MapPost("/v1/transfers", async (HttpContext context, CancellationToken ct) =>
        {
            var validation = await TryAuthenticateAsync(context, ct);
            if (!validation.IsValid)
            {
                return ApiErrorMapper.ToResult(new ApiError(
                    ErrorCodes.AuthInvalid, "Invalid or missing authorization token.", Retryable: false));
            }

            if (!MediaTypeHeaderValue.TryParse(context.Request.ContentType, out var contentType)
                || !string.Equals(contentType.MediaType.ToString(), "multipart/form-data", StringComparison.OrdinalIgnoreCase))
            {
                return ApiErrorMapper.InvalidRequest("Expected multipart/form-data.");
            }

            var logger = context.RequestServices.GetRequiredService<ILogger<KestrelReceiverHost>>();

            TransferManifest? manifest = null;
            Stream? fileStream = null;

            try
            {
                var boundary = contentType.Boundary.Value.ToString().Trim('"');
                if (string.IsNullOrEmpty(boundary))
                {
                    return ApiErrorMapper.InvalidRequest("Missing multipart boundary.");
                }

                var reader = new MultipartReader(boundary, context.Request.Body);
                MultipartSection? section;
                while ((section = await reader.ReadNextSectionAsync(ct)) is not null)
                {
                    var disposition = section.GetContentDispositionHeader();
                    var name = disposition?.Name.Value?.Trim('"');

                    if (name == "metadata")
                    {
                        if (manifest is not null)
                        {
                            return ApiErrorMapper.InvalidRequest("Duplicate metadata part.");
                        }

                        var json = await ReadLimitedStringAsync(section.Body, TransferMetadataParser.MaxMetadataBytes, ct);
                        manifest = TransferMetadataParser.Parse(json);
                    }
                    else if (name == "file")
                    {
                        if (manifest is null)
                        {
                            return ApiErrorMapper.InvalidRequest("metadata part must precede file part.");
                        }

                        if (fileStream is not null)
                        {
                            return ApiErrorMapper.InvalidRequest("Duplicate file part.");
                        }

                        fileStream = section.Body;
                        break;
                    }
                }

                if (manifest is null || fileStream is null)
                {
                    return ApiErrorMapper.InvalidRequest("Both metadata and file parts are required.");
                }

                var result = await _transferService.ReceiveAsync(manifest!, fileStream, ct);
                if (!result.IsSuccess)
                {
                    return ApiErrorMapper.ToResult(result.Error!);
                }

                return Results.Json(new
                {
                    transferId = result.Record!.TransferId,
                    status = result.Record.Status.ToString().ToLowerInvariant(),
                    receivedAt = result.Record.ReceivedAt,
                });
            }
            catch (TransferProcessingException ex)
            {
                return ApiErrorMapper.ToResult(ex.Error);
            }
            catch (JsonException)
            {
                return ApiErrorMapper.InvalidRequest("Malformed metadata JSON.");
            }
            catch (InvalidDataException)
            {
                return ApiErrorMapper.InvalidRequest("Malformed multipart body.");
            }
            catch (OperationCanceledException)
            {
                return Results.StatusCode(StatusCodes.Status499ClientClosedRequest);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Unexpected error while receiving transfer.");
                return ApiErrorMapper.ToResult(new ApiError(
                    ErrorCodes.DiskWriteFailed, "Unexpected server error.", Retryable: true));
            }
        });

        app.MapGet("/v1/transfers/{transferId}", async (string transferId, HttpContext context, CancellationToken ct) =>
        {
            var validation = await TryAuthenticateAsync(context, ct);
            if (!validation.IsValid)
            {
                return ApiErrorMapper.ToResult(new ApiError(
                    ErrorCodes.AuthInvalid, "Invalid or missing authorization token.", Retryable: false));
            }

            if (string.IsNullOrWhiteSpace(transferId) || transferId.Length > 64)
            {
                return ApiErrorMapper.InvalidRequest("Invalid transfer id.");
            }

            var record = await _transferService.GetByIdAsync(transferId, ct);
            return record is null
                ? ApiErrorMapper.NotFound()
                : Results.Json(new
                {
                    transferId = record.TransferId,
                    status = record.Status.ToString().ToLowerInvariant(),
                    receivedAt = record.ReceivedAt,
                    errorCode = record.ErrorCode,
                    localFilePath = record.LocalFilePath,
                });
        });
    }

    private async Task<TokenValidationResult> TryAuthenticateAsync(HttpContext context, CancellationToken ct)
    {
        var token = BearerTokenParser.Extract(context.Request.Headers.Authorization);
        return await _tokenValidator.ValidateAsync(token, ct);
    }

    private static async Task<string> ReadLimitedStringAsync(
        Stream stream,
        int maxBytes,
        CancellationToken cancellationToken)
    {
        using var buffer = new MemoryStream();
        var chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = await stream.ReadAsync(chunk, cancellationToken)) > 0)
        {
            total += read;
            if (total > maxBytes)
            {
                throw new TransferProcessingException(new ApiError(
                    ErrorCodes.InvalidRequest, "Metadata part too large.", Retryable: false));
            }

            await buffer.WriteAsync(chunk.AsMemory(0, read), cancellationToken);
        }

        return System.Text.Encoding.UTF8.GetString(buffer.ToArray());
    }
}