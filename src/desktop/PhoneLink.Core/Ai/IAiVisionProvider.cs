namespace PhoneLink.Core.Ai;

public interface IAiVisionProvider
{
    Task<string> SolveQuestionAsync(
        string imagePath,
        string prompt,
        CancellationToken cancellationToken);

    Task<bool> TestConnectionAsync(CancellationToken cancellationToken);
}