using System.Text.Json;
using System.Text.Json.Serialization;

namespace PhoneLink.Core.Feedback;

/// <summary>
/// 将解析后的元数据序列化为与 Android 一致的 wire JSON（Schema V1）。
/// </summary>
public static class ScannerFeedbackMetadataJson
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        Converters =
        {
            new FeedbackReasonJsonConverter(),
            new FeedbackQuadPointJsonConverter(),
            new FeedbackQuadJsonConverter(),
        },
    };

    public static string Serialize(ScannerFeedbackMetadata metadata)
        => JsonSerializer.Serialize(metadata, Options);

    private sealed class FeedbackReasonJsonConverter : JsonConverter<FeedbackReason>
    {
        public override FeedbackReason Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            throw new NotSupportedException("Parsing is handled by ScannerFeedbackMetadataParser.");
        }

        public override void Write(Utf8JsonWriter writer, FeedbackReason value, JsonSerializerOptions options)
        {
            writer.WriteStringValue(value switch
            {
                FeedbackReason.UserCorrected => "USER_CORRECTED",
                FeedbackReason.LowConfidence => "LOW_CONFIDENCE",
                FeedbackReason.ModelNotFound => "MODEL_NOT_FOUND",
                FeedbackReason.CleanSuccess => "CLEAN_SUCCESS",
                _ => throw new ArgumentOutOfRangeException(nameof(value)),
            });
        }
    }

    private sealed class FeedbackQuadPointJsonConverter : JsonConverter<FeedbackQuadPoint>
    {
        public override FeedbackQuadPoint Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            throw new NotSupportedException("Parsing is handled by ScannerFeedbackMetadataParser.");
        }

        public override void Write(Utf8JsonWriter writer, FeedbackQuadPoint value, JsonSerializerOptions options)
        {
            writer.WriteStartArray();
            writer.WriteNumberValue(value.X);
            writer.WriteNumberValue(value.Y);
            writer.WriteEndArray();
        }
    }

    private sealed class FeedbackQuadJsonConverter : JsonConverter<FeedbackQuad>
    {
        public override FeedbackQuad Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            throw new NotSupportedException("Parsing is handled by ScannerFeedbackMetadataParser.");
        }

        public override void Write(Utf8JsonWriter writer, FeedbackQuad value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            writer.WritePropertyName("tl");
            JsonSerializer.Serialize(writer, value.Tl, Options);
            writer.WritePropertyName("tr");
            JsonSerializer.Serialize(writer, value.Tr, Options);
            writer.WritePropertyName("br");
            JsonSerializer.Serialize(writer, value.Br, Options);
            writer.WritePropertyName("bl");
            JsonSerializer.Serialize(writer, value.Bl, Options);
            writer.WriteEndObject();
        }
    }
}