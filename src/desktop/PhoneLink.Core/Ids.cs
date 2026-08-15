namespace PhoneLink.Core;

public static class Ids
{
    /// <summary>短 ID 用于日志，避免泄露完整设备标识。</summary>
    public static string Short(string id, int length = 8)
    {
        if (string.IsNullOrEmpty(id))
        {
            return string.Empty;
        }

        return id.Length <= length ? id : id[..length];
    }
}