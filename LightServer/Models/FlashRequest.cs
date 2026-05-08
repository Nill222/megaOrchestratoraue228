namespace LightServer.Models;

public class FlashRequest
{
    /// <summary>
    /// Номер порта: 1-8
    /// </summary>
    public int Port { get; set; }
    
    /// <summary>
    /// Яркость: 0-100
    /// </summary>
    public int Brightness { get; set; } = 100;
    
    /// <summary>
    /// Длительность вспышки в мс
    /// </summary>
    public int DurationMs { get; set; } = 100;
}
