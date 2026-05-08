using Microsoft.AspNetCore.Mvc;
using LightServer.Models;
using LightServer.Services;

namespace LightServer.Controllers;

[ApiController]
[Route("api/[controller]")]
public class LightController : ControllerBase
{
    private readonly LightService _lightService;
    private readonly ILogger<LightController> _logger;
    
    public LightController(LightService lightService, ILogger<LightController> logger)
    {
        _lightService = lightService;
        _logger = logger;
    }
    
    /// <summary>
    /// Проверка статуса сервера
    /// </summary>
    [HttpGet("status")]
    public IActionResult GetStatus()
    {
        return Ok(new
        {
            IsConnected = _lightService.IsConnected,
            Timestamp = DateTime.UtcNow
        });
    }
    
    /// <summary>
    /// Включить свет на порту
    /// </summary>
    [HttpPost("turn-on")]
    public async Task<IActionResult> TurnOn([FromBody] SetLightRequest request)
    {
        if (request.Port < 1 || request.Port > 8)
            return BadRequest("Port must be between 1 and 8");
        
        if (request.Brightness < 0 || request.Brightness > 100)
            return BadRequest("Brightness must be between 0 and 100");
        
        var success = await _lightService.SetLightAsync(request.Port, request.Brightness, request.DurationMs);
        
        if (!success)
            return StatusCode(500, "Failed to control light");
        
        return Ok(new { success = true, message = $"Light on port {request.Port} set to {request.Brightness}%" });
    }
    
    /// <summary>
    /// Выключить свет на порту
    /// </summary>
    [HttpPost("turn-off/{port}")]
    public async Task<IActionResult> TurnOff(int port)
    {
        if (port < 1 || port > 8)
            return BadRequest("Port must be between 1 and 8");
        
        var success = await _lightService.SetLightAsync(port, 0);
        
        if (!success)
            return StatusCode(500, "Failed to turn off light");
        
        return Ok(new { success = true, message = $"Light on port {port} turned off" });
    }
    
    /// <summary>
    /// Выключить все порты
    /// </summary>
    [HttpPost("turn-off-all")]
    public async Task<IActionResult> TurnOffAll()
    {
        await _lightService.TurnOffAllAsync();
        return Ok(new { success = true, message = "All lights turned off" });
    }
    
    /// <summary>
    /// Вспышка заданной длительности
    /// </summary>
    [HttpPost("flash")]
    public async Task<IActionResult> Flash([FromBody] FlashRequest request)
    {
        if (request.Port < 1 || request.Port > 8)
            return BadRequest("Port must be between 1 and 8");
        
        if (request.Brightness < 0 || request.Brightness > 100)
            return BadRequest("Brightness must be between 0 and 100");
        
        if (request.DurationMs <= 0 || request.DurationMs > 5000)
            return BadRequest("Duration must be between 1 and 5000 ms");
        
        // Запускаем асинхронно, не блокируем ответ
        _ = Task.Run(async () =>
        {
            await _lightService.FlashAsync(request.Port, request.Brightness, request.DurationMs);
        });
        
        return Ok(new { success = true, message = $"Flash on port {request.Port} triggered" });
    }

    /// <summary>
    /// Триггер подсветки в такт инспекции камеры (camera_id -> port из appsettings).
    /// </summary>
    [HttpPost("trigger-inspection")]
    public async Task<IActionResult> TriggerInspection([FromBody] TriggerInspectionRequest request)
    {
        if (request.CameraId < 0)
            return BadRequest("camera_id must be >= 0");

        var success = await _lightService.TriggerInspectionAsync(
            request.CameraId,
            request.FrameId,
            request.Phase ?? "capture",
            request.Brightness,
            request.DurationMs
        );

        if (!success)
            return StatusCode(500, new { success = false, message = "Failed to trigger inspection light" });

        return Ok(new
        {
            success = true,
            camera_id = request.CameraId,
            frame_id = request.FrameId,
            phase = request.Phase ?? "capture"
        });
    }
}