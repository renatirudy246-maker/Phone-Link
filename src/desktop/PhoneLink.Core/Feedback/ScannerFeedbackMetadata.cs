using System.Text.Json;

namespace PhoneLink.Core.Feedback;

public enum FeedbackReason
{
    UserCorrected,
    LowConfidence,
    ModelNotFound,
    CleanSuccess,
}

public sealed record FeedbackQuadPoint(double X, double Y);

public sealed record FeedbackQuad(
    FeedbackQuadPoint Tl,
    FeedbackQuadPoint Tr,
    FeedbackQuadPoint Br,
    FeedbackQuadPoint Bl);

public sealed record ScannerFeedbackSource(int Width, int Height, string Sha256);

public sealed record ScannerFeedbackModel(string Name, string Sha256);

public sealed record ScannerFeedbackHeatmap(
    double PeakSigma,
    double PeakMargin,
    IReadOnlyList<double> PeakValues);

public sealed record ScannerFeedbackDetection(
    string Status,
    double? Confidence,
    string? QualityReason,
    double? MaskAreaRatio,
    ScannerFeedbackHeatmap? Heatmap);

public sealed record ScannerFeedbackCorrection(
    double MeanDelta,
    double MaxDelta,
    IReadOnlyList<string> AdjustedCorners,
    bool PredictionMissing);

/// <summary>
/// Scanner Feedback Metadata Schema V1。
/// detector source = EXIF 方向归一化后的 prepared 原图（perspective warp 之前）。
/// labelSource = "user_confirmed_quad"：用户操作得到的 pseudo/manual GT，不是绝对真理。
/// </summary>
public sealed record ScannerFeedbackMetadata(
    int SchemaVersion,
    string SampleId,
    string CreatedAtUtc,
    string LabelSource,
    ScannerFeedbackSource Source,
    ScannerFeedbackModel Model,
    ScannerFeedbackDetection Detection,
    FeedbackQuad? PredictedQuad,
    FeedbackQuad CorrectedQuad,
    ScannerFeedbackCorrection Correction,
    FeedbackReason Reason);

public sealed class FeedbackMetadataParseException(string message) : Exception(message);

/// <summary>
/// 严格解析 Schema V1（所有长度/范围/枚举均校验，防止坏数据落盘与路径注入）。
/// </summary>
public static class ScannerFeedbackMetadataParser
{
    public const int MaxMetadataBytes = 64 * 1024;

    private const int MaxSampleIdLength = 128;

    private static readonly HashSet<string> ValidDetectionStatuses = new(StringComparer.Ordinal)
    {
        "Detected", "LowConfidence", "NotFound",
    };

    public static ScannerFeedbackMetadata Parse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return Parse(doc.RootElement);
        }
        catch (JsonException ex)
        {
            throw new FeedbackMetadataParseException($"Malformed feedback metadata JSON: {ex.Message}");
        }
    }

    public static ScannerFeedbackMetadata Parse(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new FeedbackMetadataParseException("metadata must be a JSON object");
        }

        var schemaVersion = RequireInt(root, "schemaVersion");
        if (schemaVersion != 1)
        {
            throw new FeedbackMetadataParseException($"unsupported schemaVersion {schemaVersion}");
        }

        var sampleId = RequireString(root, "sampleId");
        if (string.IsNullOrWhiteSpace(sampleId) || sampleId.Length > MaxSampleIdLength)
        {
            throw new FeedbackMetadataParseException("invalid sampleId");
        }

        foreach (var c in sampleId)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c is '_' or '-'))
            {
                throw new FeedbackMetadataParseException("sampleId contains unsafe characters");
            }
        }

        var source = ParseSource(RequireObject(root, "source"));
        var model = ParseModel(RequireObject(root, "model"));
        var detection = ParseDetection(RequireObject(root, "detection"));
        var correctedQuad = ParseQuad(RequireObject(root, "correctedQuad"), "correctedQuad");

        FeedbackQuad? predictedQuad = null;
        if (root.TryGetProperty("predictedQuad", out var predictedEl) && predictedEl.ValueKind == JsonValueKind.Object)
        {
            predictedQuad = ParseQuad(predictedEl, "predictedQuad");
        }

        var correction = ParseCorrection(RequireObject(root, "correction"));
        var reason = ParseReason(RequireString(root, "reason"));

        return new ScannerFeedbackMetadata(
            SchemaVersion: schemaVersion,
            SampleId: sampleId,
            CreatedAtUtc: root.TryGetProperty("createdAtUtc", out var createdAt) && createdAt.ValueKind == JsonValueKind.String
                ? createdAt.GetString()!
                : string.Empty,
            LabelSource: root.TryGetProperty("labelSource", out var label) && label.ValueKind == JsonValueKind.String
                ? label.GetString()!
                : "user_confirmed_quad",
            Source: source,
            Model: model,
            Detection: detection,
            PredictedQuad: predictedQuad,
            CorrectedQuad: correctedQuad,
            Correction: correction,
            Reason: reason);
    }

    private static ScannerFeedbackSource ParseSource(JsonElement el)
    {
        var width = RequireInt(el, "width");
        var height = RequireInt(el, "height");
        var sha256 = RequireString(el, "sha256");
        if (width <= 0 || height <= 0)
        {
            throw new FeedbackMetadataParseException("invalid source dimensions");
        }

        if (!IsSha256Hex(sha256))
        {
            throw new FeedbackMetadataParseException("invalid source sha256");
        }

        return new ScannerFeedbackSource(width, height, sha256);
    }

    private static ScannerFeedbackModel ParseModel(JsonElement el)
    {
        var name = RequireString(el, "name");
        var sha256 = RequireString(el, "sha256");
        if (string.IsNullOrWhiteSpace(name) || name.Length > 64)
        {
            throw new FeedbackMetadataParseException("invalid model name");
        }

        if (!IsSha256Hex(sha256))
        {
            throw new FeedbackMetadataParseException("invalid model sha256");
        }

        return new ScannerFeedbackModel(name, sha256);
    }

    private static ScannerFeedbackDetection ParseDetection(JsonElement el)
    {
        var status = RequireString(el, "status");
        if (!ValidDetectionStatuses.Contains(status))
        {
            throw new FeedbackMetadataParseException($"invalid detection status '{status}'");
        }

        double? confidence = null;
        if (el.TryGetProperty("confidence", out var confEl) && confEl.ValueKind != JsonValueKind.Null)
        {
            confidence = confEl.GetDouble();
            if (confidence is < 0 or > 1)
            {
                throw new FeedbackMetadataParseException("invalid confidence");
            }
        }

        string? qualityReason = el.TryGetProperty("qualityReason", out var qr) && qr.ValueKind == JsonValueKind.String
            ? qr.GetString()
            : null;

        double? maskAreaRatio = null;
        if (el.TryGetProperty("maskAreaRatio", out var maskEl) && maskEl.ValueKind != JsonValueKind.Null)
        {
            maskAreaRatio = maskEl.GetDouble();
            if (maskAreaRatio is < 0 or > 1)
            {
                throw new FeedbackMetadataParseException("invalid maskAreaRatio");
            }
        }

        ScannerFeedbackHeatmap? heatmap = null;
        if (el.TryGetProperty("heatmap", out var hmEl) && hmEl.ValueKind == JsonValueKind.Object)
        {
            var sigma = RequireDouble(hmEl, "peakSigma");
            var margin = RequireDouble(hmEl, "peakMargin");
            var values = RequireDoubleArray(hmEl, "peakValues");
            if (values.Count != 4)
            {
                throw new FeedbackMetadataParseException("peakValues must have 4 entries");
            }

            heatmap = new ScannerFeedbackHeatmap(sigma, margin, values);
        }

        return new ScannerFeedbackDetection(status, confidence, qualityReason, maskAreaRatio, heatmap);
    }

    private static FeedbackQuad ParseQuad(JsonElement el, string name)
    {
        return new FeedbackQuad(
            Tl: ParsePoint(el, "tl", name),
            Tr: ParsePoint(el, "tr", name),
            Br: ParsePoint(el, "br", name),
            Bl: ParsePoint(el, "bl", name));
    }

    private static FeedbackQuadPoint ParsePoint(JsonElement el, string key, string quadName)
    {
        if (!el.TryGetProperty(key, out var pointEl) || pointEl.ValueKind != JsonValueKind.Array || pointEl.GetArrayLength() != 2)
        {
            throw new FeedbackMetadataParseException($"{quadName}.{key} must be a [x, y] pair");
        }

        var x = pointEl[0].GetDouble();
        var y = pointEl[1].GetDouble();
        if (x is < 0 or > 1 || y is < 0 or > 1)
        {
            throw new FeedbackMetadataParseException($"{quadName}.{key} out of normalized range");
        }

        return new FeedbackQuadPoint(x, y);
    }

    private static ScannerFeedbackCorrection ParseCorrection(JsonElement el)
    {
        var meanDelta = el.TryGetProperty("meanDelta", out var meanEl) && meanEl.ValueKind != JsonValueKind.Null
            ? meanEl.GetDouble()
            : 0d;
        var maxDelta = el.TryGetProperty("maxDelta", out var maxEl) && maxEl.ValueKind != JsonValueKind.Null
            ? maxEl.GetDouble()
            : 0d;
        if (meanDelta < 0 || maxDelta < 0)
        {
            throw new FeedbackMetadataParseException("invalid correction deltas");
        }

        var adjustedCorners = new List<string>();
        if (el.TryGetProperty("adjustedCorners", out var cornersEl) && cornersEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in cornersEl.EnumerateArray())
            {
                adjustedCorners.Add(item.GetString() ?? string.Empty);
            }
        }

        var predictionMissing = el.TryGetProperty("predictionMissing", out var pmEl) && pmEl.ValueKind == JsonValueKind.True;

        return new ScannerFeedbackCorrection(meanDelta, maxDelta, adjustedCorners, predictionMissing);
    }

    private static FeedbackReason ParseReason(string value) => value switch
    {
        "USER_CORRECTED" => FeedbackReason.UserCorrected,
        "LOW_CONFIDENCE" => FeedbackReason.LowConfidence,
        "MODEL_NOT_FOUND" => FeedbackReason.ModelNotFound,
        "CLEAN_SUCCESS" => FeedbackReason.CleanSuccess,
        _ => throw new FeedbackMetadataParseException($"invalid reason '{value}'"),
    };

    private static bool IsSha256Hex(string value)
        => value.Length == 64 && value.All(c => Uri.IsHexDigit(c));

    private static JsonElement RequireObject(JsonElement parent, string key)
    {
        if (!parent.TryGetProperty(key, out var el) || el.ValueKind != JsonValueKind.Object)
        {
            throw new FeedbackMetadataParseException($"missing or invalid '{key}'");
        }

        return el;
    }

    private static string RequireString(JsonElement parent, string key)
    {
        if (!parent.TryGetProperty(key, out var el) || el.ValueKind != JsonValueKind.String)
        {
            throw new FeedbackMetadataParseException($"missing or invalid '{key}'");
        }

        return el.GetString()!;
    }

    private static int RequireInt(JsonElement parent, string key)
    {
        if (!parent.TryGetProperty(key, out var el) || el.ValueKind != JsonValueKind.Number)
        {
            throw new FeedbackMetadataParseException($"missing or invalid '{key}'");
        }

        return el.GetInt32();
    }

    private static double RequireDouble(JsonElement parent, string key)
    {
        if (!parent.TryGetProperty(key, out var el) || el.ValueKind != JsonValueKind.Number)
        {
            throw new FeedbackMetadataParseException($"missing or invalid '{key}'");
        }

        return el.GetDouble();
    }

    private static IReadOnlyList<double> RequireDoubleArray(JsonElement parent, string key)
    {
        if (!parent.TryGetProperty(key, out var el) || el.ValueKind != JsonValueKind.Array)
        {
            throw new FeedbackMetadataParseException($"missing or invalid '{key}'");
        }

        return el.EnumerateArray().Select(item => item.GetDouble()).ToList();
    }
}