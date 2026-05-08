namespace LightServer.Models;

public sealed class TriggerInspectionRequest
{
    public int CameraId { get; set; }
    public long FrameId { get; set; }
    public string Phase { get; set; } = "capture";
    public int? Brightness { get; set; }
    public int? DurationMs { get; set; }
}
