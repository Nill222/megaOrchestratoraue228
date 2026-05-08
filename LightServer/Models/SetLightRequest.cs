namespace LightServer.Models;

public class SetLightRequest
{
    /// <summary>
    /// Номер порта: 1-8
    /// </summary>
    public int Port { get; set; }
    
    /// <summary>
    /// Яркость: 0-100
    /// </summary>
    public int Brightness { get; set; }
    
    /// <summary>
    /// Длительность вспышки в мс (0 = постоянно)
    /// </summary>
    public int DurationMs { get; set; } = 0;
}
