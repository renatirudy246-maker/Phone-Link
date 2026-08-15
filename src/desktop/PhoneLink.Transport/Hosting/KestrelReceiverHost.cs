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
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Transport.Http;

namespace PhoneLink.Transport.Hosting;

/// <summary>
/// 局域网 HTTPS Receiver（Kestrel）。端点：
///   GET  /v1/health                预配对最小响应（protocolVersion/status）/ 带设备 token 完整响应
///   POST /v1/pair                  一次性 PairingSession 交换长期 Device Token
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
    private readonly IPairingSessionService _pairingSessionService;
    private readonly IPairedDeviceRepository _pairedDeviceRepository;

    private WebApplication? _app;
    private volatile bool _isPaused;

    public bool IsPaused => _isPaused;

    public void Pause() => _isPaused = true;

    public void Resume() => _isPaused = false;

    public KestrelReceiverHost(
        ReceiverOptions options,
        IDeviceIdentityProvider deviceIdentity,
        ITokenValidator tokenValidator,
        ITransferService transferService,
        ILoggerFactory loggerFactory,
        ITlsCertificateProvider certificateProvider,
        IPairingSessionService pairingSessionService,
        IPairedDeviceRepository pairedDeviceRepository)
    {
        _options = options;
        _deviceIdentity = deviceIdentity;
        _tokenValidator = tokenValidator;
        _transferService = transferService;
        _loggerFactory = loggerFactory;
        _certificateProvider = certificateProvider;
        _pairingSessionService = pairingSessionService;
        _pairedDeviceRepository = pairedDeviceRepository;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Services.AddSingleton(_options);
        builder.Services.AddSingleton(_deviceIdentity);
        builder.Services.AddSingleton(_tokenValidator);
        builder.Services.AddSingleton(_transferService);
        builder.Services.AddSingleton(_pairingSessionService);
        builder.Services.AddSingleton(_pairedDeviceRepository);
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
            var presentedToken = BearerTokenParser.Extract(context.Request.Headers.Authorization);
            if (presentedToken is null)
            {
                return Results.Json(new
                {
                    protocolVersion = AppInfo.ProtocolVersion,
                    status = "ok",
                });
            }

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

            return AuthError(validation);
        });

        app.MapPost("/v1/pair", async (HttpContext context, CancellationToken ct) =>
        {
            var logger = context.RequestServices.GetRequiredService<ILogger<KestrelReceiverHost>>();

            PairRequest request;
            try
            {
                request = await ReadJsonAsync<PairRequest>(context.Request.Body, ct);
            }
            catch (Exception ex) when (ex is JsonException or InvalidDataException or NotSupportedException)
            {
                return ApiErrorMapper.InvalidRequest("Malformed pairing request.");
            }

            if (request.ProtocolVersion is not null && request.ProtocolVersion != AppInfo.ProtocolVersion)
            {
                return ApiErrorMapper.ToResult(new ApiError(
                    ErrorCodes.UnsupportedProtocol,
                    $"Unsupported protocol version {request.ProtocolVersion}.",
                    Retryable: false));
            }

            var consume = await _pairingSessionService.ConsumeAsync(request.OneTimeToken ?? string.Empty, ct);
            if (consume.ErrorCode is not null || consume.Session is null)
            {
                return ApiErrorMapper.ToResult(new ApiError(
                    consume.ErrorCode ?? ErrorCodes.PairTokenInvalid,
                    "Pairing token rejected.",
                    Retryable: false));
            }

            var session = consume.Session;
            if (string.IsNullOrWhiteSpace(request.MobileDeviceId) || request.MobileDeviceId.Length > 128
                || string.IsNullOrWhiteSpace(request.MobileDeviceName) || request.MobileDeviceName.Length > 128
                || string.IsNullOrWhiteSpace(request.Platform) || request.Platform.Length > 16)
            {
                return ApiErrorMapper.InvalidRequest("Invalid mobile device fields.");
            }

            var deviceToken = TokenGenerator.GenerateSecureToken();
            var device = new PairedDevice(
                DeviceId: request.MobileDeviceId!,
                DisplayName: request.MobileDeviceName!,
                Platform: request.Platform!.ToLowerInvariant(),
                AuthTokenReference: Convert.ToHexString(
                    System.Security.Cryptography.SHA256.HashData(
                        System.Text.Encoding.UTF8.GetBytes(deviceToken))),
                CertificateFingerprint: session.CertificateFingerprint,
                LastSeenAt: DateTimeOffset.UtcNow,
                LastKnownEndpoint: session.Endpoint,
                IsTrusted: true);

            await _pairedDeviceRepository.UpsertAsync(device, ct);

            logger.LogInformation(
                "Device paired: {DeviceId} ({Platform}), session {SessionId}.",
                Ids.Short(device.DeviceId), device.Platform, Ids.Short(session.SessionId));

            return Results.Json(new
            {
                deviceToken,
                desktopDeviceId = session.DesktopDeviceId,
                protocolVersion = AppInfo.ProtocolVersion,
            });
        });

        app.MapPost("/v1/transfers", async (HttpContext context, CancellationToken ct) =>
        {
            var validation = await TryAuthenticateAsync(context, ct);
            if (!validation.IsValid)
            {
                return AuthError(validation);
            }

            if (_isPaused)
            {
                return ApiErrorMapper.ToResult(new ApiError(
                    ErrorCodes.ServicePaused, "Receiving is paused.", Retryable: true));
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
                var boundary = contentType.Boundary.Value?.ToString().Trim('"') ?? string.Empty;
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
                return AuthError(validation);
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

    private static IResult AuthError(TokenValidationResult validation)
    {
        var code = validation.ErrorCode == ErrorCodes.DeviceRevoked
            ? ErrorCodes.DeviceRevoked
            : ErrorCodes.AuthInvalid;
        return ApiErrorMapper.ToResult(new ApiError(
            code, "Invalid or missing authorization token.", Retryable: false));
    }

    private static async Task<T> ReadJsonAsync<T>(Stream stream, CancellationToken cancellationToken)
    {
        using var buffer = new MemoryStream();
        var chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = await stream.ReadAsync(chunk, cancellationToken)) > 0)
        {
            total += read;
            if (total > 8192)
            {
                throw new InvalidDataException("Request body too large.");
            }

            await buffer.WriteAsync(chunk.AsMemory(0, read), cancellationToken);
        }

        return JsonSerializer.Deserialize<T>(buffer.ToArray(),
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
            ?? throw new JsonException("Empty JSON body.");
    }

    private sealed class PairRequest
    {
        public string? OneTimeToken { get; set; }
        public string? MobileDeviceId { get; set; }
        public string? MobileDeviceName { get; set; }
        public string? Platform { get; set; }
        public int? ProtocolVersion { get; set; }
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