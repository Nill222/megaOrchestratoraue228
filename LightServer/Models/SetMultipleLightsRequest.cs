namespace LightServer.Models;

public class SetMultipleLightsRequest
{
    /// <summary>
    /// Маска портов (битовая)
    /// </summary>
    public byte PortMask { get; set; }
    
    /// <summary>
    /// Яркость: 0-100
    /// </summary>
    public int Brightness { get; set; }
}
