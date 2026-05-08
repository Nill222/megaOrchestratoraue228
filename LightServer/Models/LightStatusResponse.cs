namespace LightServer.Models;

public class LightStatusResponse
{
    public bool IsConnected { get; set; }
    public int[] PortsBrightness { get; set; } = null!;
    public string ComPort { get; set; } = string.Empty;
}
