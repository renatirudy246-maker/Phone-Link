using System.Text.Json;
using PhoneLink.Core.Feedback;

namespace PhoneLink.Core.Tests;

public class ScannerFeedbackMetadataParserTests
{
    private const string ModelSha256 = "aaef348eb81709d26f7e8974401795b141d70ba88bc69792c779fbae102eadaa";

    private static string ValidJson(string? predictedQuad = null, bool includeCorrectedQuad = true)
    {
        predictedQuad ??= """
                "predictedQuad": {
                  "tl": [0.1, 0.1], "tr": [0.9, 0.1], "br": [0.9, 0.9], "bl": [0.1, 0.9]
                },
            """;
        var correctedQuad = includeCorrectedQuad
            ? """
              "correctedQuad": {
                "tl": [0.1, 0.1], "tr": [0.9, 0.1], "br": [0.9, 0.9], "bl": [0.1, 0.9]
              },
            """
            : string.Empty;
        return $$"""
            {
              "schemaVersion": 1,
              "sampleId": "sf-test-123",
              "createdAtUtc": "2026-08-16T12:00:00Z",
              "labelSource": "user_confirmed_quad",
              "source": { "width": 1000, "height": 2000, "sha256": "{{new string('A', 64)}}" },
              "model": { "name": "DocQuadNet-256", "sha256": "{{ModelSha256}}" },
              "detection": {
                "status": "Detected",
                "confidence": 0.9,
                "qualityReason": null,
                "maskAreaRatio": 0.7,
                "heatmap": null
              },
              {{predictedQuad}}
              {{correctedQuad}}
              "correction": {
                "meanDelta": 0.01,
                "maxDelta": 0.014,
                "adjustedCorners": ["TR"],
                "predictionMissing": false
              },
              "reason": "USER_CORRECTED"
            }
            """;
    }

    [Fact]
    public void ParsesValidMetadata()
    {
        var metadata = ScannerFeedbackMetadataParser.Parse(ValidJson());

        Assert.Equal(1, metadata.SchemaVersion);
        Assert.Equal("sf-test-123", metadata.SampleId);
        Assert.Equal("user_confirmed_quad", metadata.LabelSource);
        Assert.Equal(FeedbackReason.UserCorrected, metadata.Reason);
        Assert.Equal(1000, metadata.Source.Width);
        Assert.Equal(2000, metadata.Source.Height);
        Assert.Equal("DocQuadNet-256", metadata.Model.Name);
        Assert.Equal(ModelSha256, metadata.Model.Sha256);
        Assert.Equal("Detected", metadata.Detection.Status);
        Assert.Equal(0.9, metadata.Detection.Confidence);
        Assert.Null(metadata.Detection.QualityReason);
        Assert.Equal(0.7, metadata.Detection.MaskAreaRatio);
        Assert.Null(metadata.Detection.Heatmap);
        Assert.NotNull(metadata.PredictedQuad);
        Assert.Equal(0.1, metadata.PredictedQuad!.Tl.X);
        Assert.Equal(0.014, metadata.Correction.MaxDelta);
        Assert.Equal(["TR"], metadata.Correction.AdjustedCorners);
        Assert.False(metadata.Correction.PredictionMissing);
    }

    [Fact]
    public void ParsesNullPredictedQuad()
    {
        var metadata = ScannerFeedbackMetadataParser.Parse(
            ValidJson(""" "predictedQuad": null, """));
        Assert.Null(metadata.PredictedQuad);
    }

    [Fact]
    public void RejectsWrongSchemaVersion()
    {
        var json = ValidJson().Replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsUnsafeSampleId()
    {
        var json = ValidJson().Replace("\"sf-test-123\"", "\"sf/../../evil\"");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsMissingCorrectedQuad()
    {
        var json = ValidJson(includeCorrectedQuad: false);
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsUnknownReason()
    {
        var json = ValidJson().Replace("\"USER_CORRECTED\"", "\"SOMEONE_ELSE\"");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsOutOfRangeConfidence()
    {
        var json = ValidJson().Replace("\"confidence\": 0.9", "\"confidence\": 1.5");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsBadSha256()
    {
        var json = ValidJson().Replace(new string('A', 64), "not-a-sha");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsOutOfRangeQuadPoint()
    {
        var json = ValidJson().Replace("[0.1, 0.1]", "[1.5, 0.1]");
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse(json));
    }

    [Fact]
    public void RejectsMalformedJson()
    {
        Assert.Throws<FeedbackMetadataParseException>(() => ScannerFeedbackMetadataParser.Parse("{not json"));
    }

    [Fact]
    public void SerializeRoundTripsThroughParser()
    {
        var parsed = ScannerFeedbackMetadataParser.Parse(ValidJson());
        var json = ScannerFeedbackMetadataJson.Serialize(parsed);
        var roundTripped = ScannerFeedbackMetadataParser.Parse(json);

        Assert.Equal(parsed.SampleId, roundTripped.SampleId);
        Assert.Equal(parsed.Reason, roundTripped.Reason);
        Assert.Equal("USER_CORRECTED", JsonDocument.Parse(json).RootElement.GetProperty("reason").GetString());
        Assert.Equal(parsed.CorrectedQuad.Tr.X, roundTripped.CorrectedQuad.Tr.X);
    }
}