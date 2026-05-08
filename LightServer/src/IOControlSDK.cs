using System;
using System.Collections.Generic;
using System.Text;
using System.Runtime.InteropServices;

namespace NsIOControllerSDK
{
    class CIOControllerSDK
    {
        ////////////////////////////////////常量定义///////////////////////////
        // 错误码定义
        // 正确码定义
        public const Int32 MV_OK = unchecked((Int32)0x00000000);     ///< 成功，无错误

        // 通用错误码定义:范围0x80000000-0x800000FF
        public const Int32 MV_E_HANDLE = unchecked((Int32)0x80000000);     ///< 错误或无效的句柄
        public const Int32 MV_E_SUPPORT = unchecked((Int32)0x80000001);     ///< 不支持的功能
        public const Int32 MV_E_BUFOVER = unchecked((Int32)0x80000002);     ///< 缓存已满
        public const Int32 MV_E_CALLORDER = unchecked((Int32)0x80000003);     ///< 函数调用顺序错误
        public const Int32 MV_E_PARAMETER = unchecked((Int32)0x80000004);     ///< 错误的参数
        public const Int32 MV_E_RESOURCE = unchecked((Int32)0x80000006);     ///< 资源申请失败
        public const Int32 MV_E_NODATA = unchecked((Int32)0x80000007);     ///< 无数据
        public const Int32 MV_E_PRECONDITION = unchecked((Int32)0x80000008);     ///< 前置条件有误，或运行环境已发生变化
        public const Int32 MV_E_VERSION = unchecked((Int32)0x80000009);     ///< 版本不匹配
        public const Int32 MV_E_NOENOUGH_BUF = unchecked((Int32)0x8000000A);     ///< 传入的内存空间不足
        public const Int32 MV_E_MATCH = unchecked((Int32)0x8000000B);     ///< 返回信息不匹配
        public const Int32 MV_E_UPDATING = unchecked((Int32)0x8000000C);     ///< 处于升级状态
        public const Int32 MV_E_UNKNOW = unchecked((Int32)0x800000FF);     ///< 未知的错误

        // GenICam系列错误:范围0x80000100-0x800001FF
        public const Int32 MV_E_GC_GENERIC = unchecked((Int32)0x80000100);     ///< 通用错误
        public const Int32 MV_E_GC_ARGUMENT = unchecked((Int32)0x80000101);     ///< 参数非法
        public const Int32 MV_E_GC_RANGE = unchecked((Int32)0x80000102);     ///< 值超出范围
        public const Int32 MV_E_GC_PROPERTY = unchecked((Int32)0x80000103);     ///< 属性
        public const Int32 MV_E_GC_RUNTIME = unchecked((Int32)0x80000104);     ///< 运行环境有问题
        public const Int32 MV_E_GC_LOGICAL = unchecked((Int32)0x80000105);     ///< 逻辑错误
        public const Int32 MV_E_GC_ACCESS = unchecked((Int32)0x80000106);     ///< 访问权限有误
        public const Int32 MV_E_GC_TIMEOUT = unchecked((Int32)0x80000107);     ///< 超时
        public const Int32 MV_E_GC_DYNAMICCAST = unchecked((Int32)0x80000108);     ///< 转换异常
        public const Int32 MV_E_GC_UNKNOW = unchecked((Int32)0x800001FF);     ///< GenICam未知错误

        // GigE_STATUS对应的错误码:范围0x80000200-0x800002FF
        public const Int32 MV_E_NOT_IMPLEMENTED = unchecked((Int32)0x80000200);     ///< 命令不被设备支持
        public const Int32 MV_E_INVALID_ADDRESS = unchecked((Int32)0x80000201);     ///< 访问的目标地址不存在
        public const Int32 MV_E_WRITE_PROTECT = unchecked((Int32)0x80000202);     ///< 目标地址不可写
        public const Int32 MV_E_ACCESS_DENIED = unchecked((Int32)0x80000203);     ///< 访问无权限
        public const Int32 MV_E_BUSY = unchecked((Int32)0x80000204);     ///< 设备忙，或网络断开
        public const Int32 MV_E_PACKET = unchecked((Int32)0x80000205);     ///< 网络包数据错误
        public const Int32 MV_E_NETER = unchecked((Int32)0x80000206);     ///< 网络相关错误

        // USB_STATUS对应的错误码:范围0x80000300-0x800003FF
        public const Int32 MV_E_USB_READ = unchecked((Int32)0x80000300);     ///< 读usb出错
        public const Int32 MV_E_USB_WRITE = unchecked((Int32)0x80000301);     ///< 写usb出错
        public const Int32 MV_E_USB_DEVICE = unchecked((Int32)0x80000302);     ///< 设备异常
        public const Int32 MV_E_USB_GENICAM = unchecked((Int32)0x80000303);     ///< GenICam相关错误
        public const Int32 MV_E_USB_BANDWIDTH = unchecked((Int32)0x80000304);     ///< 带宽不足  该错误码新增
        public const Int32 MV_E_USB_UNKNOW = unchecked((Int32)0x800003FF);     ///< USB未知的错误

        // 升级时对应的错误码:范围0x80000400-0x800004FF
        public const Int32 MV_E_UPG_FILE_MISMATCH = unchecked((Int32)0x80000400);     ///< 升级固件不匹配
        public const Int32 MV_E_UPG_LANGUSGE_MISMATCH = unchecked((Int32)0x80000401);     ///< 升级固件语言不匹配
        public const Int32 MV_E_UPG_CONFLICT = unchecked((Int32)0x80000402);     ///< 升级冲突（设备已经在升级了再次请求升级即返回此错误）
        public const Int32 MV_E_UPG_INNER_ERR = unchecked((Int32)0x80000403);     ///< 升级时相机内部出现错误
        public const Int32 MV_E_UPG_UNKNOW = unchecked((Int32)0x800004FF);     ///< 升级时未知错误


        public const int nMaxDeviceNum = 32;

        public static IntPtr[] m_hDeviceHandleList = new IntPtr[nMaxDeviceNum];

        public static string[] m_hDeviceTypeList = new string[nMaxDeviceNum];  // 存储扩展板类型



        public static int nComIndex = 0;

        public enum MV_IO_PATTERN_OUT
        {
            MV_IO_PATTERN_PWM = 0x05,
            MV_IO_PATTERN_SINGLE = 0x06,
        };

        public enum MV_GIO_PATTERN_OUT
        {
            MV_GIO_PATTERN_SINGLE = 0x00,
            MV_GIO_PATTERN_PWM = 0x01,

        };

        // Port口定义
        public enum MV_IO_PORT_NUMBER
        {
            MV_IO_PORT_1 = 0x1,
            MV_IO_PORT_2 = 0x2,
            MV_IO_PORT_3 = 0x4,
            MV_IO_PORT_4 = 0x8,
            MV_IO_PORT_5 = 0x10,
            MV_IO_PORT_6 = 0x20,
            MV_IO_PORT_7 = 0x40,
            MV_IO_PORT_8 = 0x80,
            MV_IO_PORT_9 = 0x100,
            MV_IO_PORT_10 = 0x200,
            MV_IO_PORT_11 = 0x400,
            MV_IO_PORT_12 = 0x800,
            MV_IO_PORT_13 = 0x1000,
            MV_IO_PORT_14 = 0x2000,
            MV_IO_PORT_15 = 0x4000,
            MV_IO_PORT_16 = 0x8000,
        };

        // MainPort口定义
        public enum MV_IO_MAINPORT_NUMBER
        {
            MV_MAINIO_PORT_1 = 0x1,
            MV_MAINIO_PORT_2 = 0x2,
            MV_MAINIO_PORT_3 = 0x4,
            MV_MAINIO_PORT_4 = 0x8,
            MV_MAINIO_PORT_5 = 0x10,
            MV_MAINIO_PORT_6 = 0x20,
            MV_MAINIO_PORT_7 = 0x40,
            MV_MAINIO_PORT_8 = 0x80,

            MV_MAINIO_PORT_9 = 0x100,
            MV_MAINIO_PORT_10 = 0x200,
            MV_MAINIO_PORT_11 = 0x400,
            MV_MAINIO_PORT_12 = 0x800,
            MV_MAINIO_PORT_13 = 0x1000,
            MV_MAINIO_PORT_14 = 0x2000,
            MV_MAINIO_PORT_15 = 0x4000,
            MV_MAINIO_PORT_16 = 0x8000,
        };

        public enum MV_GIO_LEVEL
        {
            MV_GIO_LEVEL_LOW = 0x0000,
            MV_GIO_LEVEL_HIGH = 0x0001,
        }

        // 使能PNP NPN
        public enum MV_IO_PNP_TYPE
        {
            MV_IO_ENABLE_PNP = 0x01,
            MV_IO_ENABLE_NPN = 0x02,
        };


        // 上升沿下降沿定义
        public enum MV_IO_EDGE_TYPE
        {
            MV_IO_EDGE_UNKNOW = 0x00,
            MV_IO_EDGE_RISING = 0x01,
            MV_IO_EDGE_FALLING = 0x02,
        };

        // 使能开始和结束定义
        public enum MV_IO_ENABLE_TYPE
        {
            MV_IO_ENABLE_START = 0x00,
            MV_IO_ENABLE_END = 0x01,
        };


        public enum MV_IO_LEVEL
        {
            MV_IO_LEVEL_LOW = 0x0000,
            MV_IO_LEVEL_HIGH = 0x0001,
        };

        //COM Config
        public enum COM_NUMBER
        {
            RS232 = 0x00,
            RS485 = 0x01,
            RS422 = 0x02,
        };

        //兼容新旧硬件
        public enum MV_COM_HARD_TYPE
        {
            MV_HARD_OLD_VC3000 = 0x00,
            MV_HARD_NEW_VC3000 = 0x01,
            MV_HARD_VT3000 = 0x02,
            MV_HARD_VC3000X = 0x03,
            MV_HARD_VT2000 = 0x04,
            MV_HARD_VC2000 = 0x05,
        };

        // 光源状态定义
        public enum MV_IO_LIGHTSTATE
        {
            MV_IO_LIGHTSTATE_ON = 0x0001,      // 常亮
            MV_IO_LIGHTSTATE_OFF = 0x0002,     // 常灭
            MV_IO_LIGHTSTATE_TRIGGER = 0x0003, //触发
        };

        // 沿触发定义
        public enum MV_IO_EDGE
        {
            MV_IO_EDGE_RISING = 0x01,  // 上升
            MV_IO_EDGE_DOWN = 0x02,    // 下降
        };

        public enum MV_IO_EDGE_NOTICE_STATE
        {
            MV_IO_EDGE_NOTICE_DISABLE = 0x00,
            MV_IO_EDGE_NOTICE_ENABLE = 0x01,
        };

        //GPIO设备类型，用于paraCfg.xml兼容不同设备寄存器地址
        public enum MV_MAINIO_DEVICE_TYPE
        {
            MV_DEVICE_VC3000 = 0x00,   // VC3000设备
            MV_DEVICE_VT3000 = 0x01,   // VT3000设备
        };

        public struct MV_IO_SERIAL
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
            public String strComName;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;
        };

        /** @struct MV_IO_OUTPUT_ENABLE
         *  @brief 输出使能
         */
        public struct MV_IO_OUTPUT_ENABLE
        {
            public ushort nPortNumber;            // 使能端口，若多个端口同时使能可以执行或运算
            public Byte nType;                  // MV_IO_ENABLE_START 表示使能开始，MV_IO_ENABLE_END表示使能结束
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 4)]
            public UInt32[] nReserved;
        };

        // 沿检测参数
        public struct MV_IO_INPUT_EDGE_TYPE
        {
            public Byte nPortNumber;            // 使能端口，若多个端口同时使能可以执行或运算
            public UInt16 nTriggerTimes;        //沿触发次数
            public MV_IO_EDGE_TYPE enEdgeType;//触发沿
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;
        };

        public struct MV_IO_LIGHT_PARAM
        {
            public Byte nPortNumber;          // 光源关联端口号
            public UInt16 nLightValue;          // 光源亮度
            public UInt16 nLightState;          // 触发后常亮还是常灭
            public UInt16 nLightEdge;           // 上升沿或下降沿触发
            public UInt16 nDurationTime;        // 亮灯持续时间
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 3)]
            public UInt32[] nReserved;
        };

        public struct MV_IO_LIGHTSOURCE_PARAM
        {
            public UInt16 nLightPortNum;            // 光源端口
            public UInt16 nInputPortNum;            // 光源触发输入端口，PORT_1代表输入1
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 4)]
            public UInt32[] nReserved;
        };

        public struct MV_IO_INPUT_LEVEL
        {
            public Byte nPortNumber;    // 高7位置0；低1位代表显示哪一位I/O，如0001使能1号口，0011代表使能1号和2号口，1001代表使能1号和4号口，以此类推；若无IO信息置0；
            public Byte nLevel0;        // 1号口电平信息
            public Byte nLevel1;        // 2号口电平信息
            public Byte nLevel2;        // 3号口电平信息
            public Byte nLevel3;        // 4号口电平信息
            public Byte nLevel4;        // 5号口电平信息
            public Byte nLevel5;        // 6号口电平信息
            public Byte nLevel6;        // 7号口电平信息
            public Byte nLevel7;        // 8号口电平信息
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;   // 保留字节
        };



        public struct MV_IO_VERSION
        {
            public UInt32 nMainVersion;     // 主版本号
            public UInt32 nSubVersion;      // 次版本号
            public UInt32 nModifyVersion;   // 修订版本号

            public UInt32 nYear;            // 年
            public UInt32 nMonth;           // 月
            public UInt32 nDay;             // 日
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;   // 保留字节
        };

        // 输入端口设置
        public struct MV_IO_SET_INPUT
        {
            public UInt32 nPort;           // 输入端口
            public UInt32 nEnable;         // 使能状态
            public UInt32 nEdge;           // 触发沿
            public UInt32 nDelayTime;       // 触发延迟
            public UInt32 nGlitch;          // 去抖时间
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;   // 保留字节
        };

        // 输出端口设置
        public struct MV_IO_SET_OUTPUT
        {
            public UInt32 nPort;            // 输出端口
            public UInt32 nType;            // 输出模式
            public UInt32 nPulseWidth;      // 脉冲宽度
            public UInt32 nPulsePeriod;     // 脉冲周期
            public UInt32 nDurationTime;    // 脉冲持续时间
            public UInt32 nValidLevel;      // 有效电平
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;   // 保留字节
        }

        // 端口号设置
        public struct MV_RS_CONFIG
        {
            public UInt32 nCOM1Type;            // COM1类型
            public UInt32 nCOM2Type;            // COM2类型
            public UInt32 nCOM3Type;            // COM2类型
            public UInt32 nCOM4Type;            // COM2类型
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 6)]
            public UInt32[] nReserved;   // 保留字节
        }


        //输入关联输出端口参数设置
        public struct MV_IO_ASSOCIATEPORT_PARAM
        {
            public UInt16 nInPortNum;            // 被关联的输入端口
            public UInt16 nOutPortNum;           // 关联的输出端口，(PORT_1)代表关联1号口，(PORT_1 | PORT_2)代表关联1号和2号口，(以此类推)
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 4)]
            public UInt32[] nReserved;
        }

        public struct MV_IO_OUTPUTSOURCE_PARAM
        {
            public UInt16 nOutPortNum;            // 输出端口
            public UInt16 nInPortNum;             // 输出的触发输入端口，(PORT_1)代表输入1
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 4)]
            public UInt32[] nReserved;
        }

        // 固件升级状态
        public struct MV_IO_LOCAL_UPGRADE
        {
            public UInt32 nProcPercent;     // 升级进度
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
            public String strProcState;     // 升级状态
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public UInt32[] nReserved;   // 保留字节
        }



        public delegate void cbOutputdelegate(IntPtr handle, ref MV_IO_INPUT_EDGE_TYPE stParam, IntPtr pUser);


        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetDebugView")]
        private static extern void MV_IO_SetDebugView(Int32 nFlag);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_CreateHandle")]
        private static extern Int32 MV_IO_CreateHandle(ref IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_DestroyHandle")]
        private static extern Int32 MV_IO_DestroyHandle(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetSDKVersion")]
        private static extern Int32 MV_IO_GetSDKVersion(ref MV_IO_VERSION pstVersion);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Open")]
        private static extern Int32 MV_IO_Open(IntPtr handle, ref MV_IO_SERIAL pstSerial);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Close")]
        private static extern void MV_IO_Close(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetLightParam")]
        private static extern Int32 MV_IO_SetLightParam(IntPtr handle, ref MV_IO_LIGHT_PARAM pstLightParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetInputLevel")]
        private static extern Int32 MV_IO_GetInputLevel(IntPtr handle, ref MV_IO_INPUT_LEVEL pstInputLevel);


        // 更新主板io接口6个
        // 初始化winio 主板io
        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetMainIODeviceType")]
        private static extern Int32 MV_IO_SetMainIODeviceType(MV_MAINIO_DEVICE_TYPE enDeviceType);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_WinIO_Init")]
        private static extern Int32 MV_IO_WinIO_Init();

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_WinIO_DeInit")]
        private static extern Int32 MV_IO_WinIO_DeInit();

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_MainIOInit")]
        private static extern Int32 MV_IO_MainIOInit();

        // 获取主板io电平状态
        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetMainInputLevel")]
        private static extern Int32 MV_IO_GetMainInputLevel(ref byte nStatus); //unsigned char *pStatus


        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetMainGPO_NPN")]
        private static extern Int32 MV_IO_SetMainGPO_NPN(Int32 nEnable);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetMainOutputLevel")]
        private static extern Int32 MV_IO_SetMainOutputLevel(MV_IO_MAINPORT_NUMBER nPortIn, MV_GIO_LEVEL nStatus);


        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetRSConfig")]
        private static extern Int32 MV_IO_SetRSConfig(ref MV_RS_CONFIG stParam, MV_COM_HARD_TYPE oType);


        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetFirmwareVersion")]
        private static extern Int32 MV_IO_GetFirmwareVersion(IntPtr handle, ref MV_IO_VERSION pstVersion);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_LocalUpgrade")]
        private static extern Int32 MV_IO_LocalUpgrade(IntPtr handle, byte[] pData, uint nLen, ref MV_IO_LOCAL_UPGRADE pstLocalUpgrade);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetPortInputParam")]
        private static extern Int32 MV_IO_GetPortInputParam(IntPtr handle, ref MV_IO_SET_INPUT stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetPortOutputParam")]
        private static extern Int32 MV_IO_GetPortOutputParam(IntPtr handle, ref MV_IO_SET_OUTPUT stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetInput")]
        private static extern Int32 MV_IO_SetInput(IntPtr handle, ref MV_IO_SET_INPUT stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetOutput")]
        private static extern Int32 MV_IO_SetOutput(IntPtr handle, ref MV_IO_SET_OUTPUT stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_AssociatedOutPort")]
        private static extern Int32 MV_IO_AssociatedOutPort(IntPtr handle, ref MV_IO_ASSOCIATEPORT_PARAM stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetOutputEnable")]
        private static extern Int32 MV_IO_SetOutputEnable(IntPtr handle, ref MV_IO_OUTPUT_ENABLE stParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetPNPEnable")]
        private static extern Int32 MV_IO_GetPNPEnable(IntPtr handle, ref MV_IO_PNP_TYPE nType);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_ExcutePNPEnable")]
        private static extern Int32 MV_IO_ExcutePNPEnable(IntPtr handle, Int32 nType);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_RegisterEdgeDetectionCallBack")]
        private static extern Int32 MV_IO_RegisterEdgeDetectionCallBack(IntPtr handle, cbOutputdelegate cbOutput, IntPtr pUser);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_ResetParam")]
        private static extern Int32 MV_IO_ResetParam(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Reboot")]
        private static extern Int32 MV_IO_Reboot(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetLightParam")]
        private static extern Int32 MV_IO_GetLightParam(IntPtr handle, ref MV_IO_LIGHT_PARAM pstLightParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SaveParam")]
        private static extern Int32 MV_IO_SaveParam(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetLightTriggerSource")]
        private static extern Int32 MV_IO_SetLightTriggerSource(IntPtr handle, ref MV_IO_LIGHTSOURCE_PARAM pstSourceParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetLightTriggerSource")]
        private static extern Int32 MV_IO_GetLightTriggerSource(IntPtr handle, ref MV_IO_LIGHTSOURCE_PARAM pstSourceParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetOutPortTriggerSource")]
        private static extern Int32 MV_IO_SetOutPortTriggerSource(IntPtr handle, ref MV_IO_OUTPUTSOURCE_PARAM pstSourceParam);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetOutPortTriggerSource")]
        private static extern Int32 MV_IO_GetOutPortTriggerSource(IntPtr handle, ref MV_IO_OUTPUTSOURCE_PARAM pstSourceParam);

        public enum LIGHT_STATE
        {
            常亮 = 1,
            常灭 = 2,
            触发 = 3,
        };

        public static void MV_IO_SetDebugView_CS(Int32 nFlag)
        {
            MV_IO_SetDebugView(nFlag);
        }

        public static Int32 MV_IO_CreateHandle_CS(ref IntPtr handle)
        {
            return MV_IO_CreateHandle(ref handle);
        }

        public static Int32 MV_IO_DestroyHandle_CS(IntPtr handle)
        {
            return MV_IO_DestroyHandle(handle);
        }

        public static Int32 MV_IO_GetSDKVersion_CS(ref MV_IO_VERSION pstVersion)
        {
            return MV_IO_GetSDKVersion(ref pstVersion);
        }

        public static Int32 MV_IO_Open_CS(IntPtr handle, ref MV_IO_SERIAL pstSerial)
        {
            return MV_IO_Open(handle, ref pstSerial);
        }

        public static Int32 MV_IO_SetLightParam_CS(IntPtr handle, ref MV_IO_LIGHT_PARAM pstLightParam)
        {
            return MV_IO_SetLightParam(handle, ref pstLightParam);
        }

        public static void MV_IO_Close_CS(IntPtr handle)
        {
            MV_IO_Close(handle);
        }

        public static Int32 MV_IO_GetInputLevel_CS(IntPtr handle, ref MV_IO_INPUT_LEVEL pstInputLevel)
        {
            return MV_IO_GetInputLevel(handle, ref pstInputLevel);
        }

        //设置GPIO主板类型
        public static Int32 MV_IO_SetMainIODeviceType_CS(MV_MAINIO_DEVICE_TYPE enDeviceType)
        {
            return MV_IO_SetMainIODeviceType(enDeviceType);
        }

        // 初始化winio
        public static Int32 MV_IO_WinIO_Init_CS()
        {
            return MV_IO_WinIO_Init();
        }

        // 释放winio
        public static Int32 MV_IO_WinIO_DeInit_CS()
        {
            return MV_IO_WinIO_DeInit();
        }

        // 初始化主板io
        public static Int32 MV_IO_MainIOInit_CS()
        {
            return MV_IO_MainIOInit();
        }

        public static Int32 MV_IO_GetMainInputLevel_CS(ref byte nStatus)
        {
            return MV_IO_GetMainInputLevel(ref nStatus);
        }

        public static Int32 MV_IO_SetMainGPO_NPN_CS(Int32 nEnable)
        {
            return MV_IO_SetMainGPO_NPN(nEnable);
        }


        public static Int32 MV_IO_SetMainOutputLevel_CS(MV_IO_MAINPORT_NUMBER nPortIn, MV_GIO_LEVEL nStatus)
        {
            return MV_IO_SetMainOutputLevel(nPortIn, nStatus);
        }


        public static Int32 MV_IO_SetRSConfig_CS(ref MV_RS_CONFIG stParam, MV_COM_HARD_TYPE oType)
        {
            return MV_IO_SetRSConfig(ref stParam, oType);
        }

        public static Int32 MV_IO_GetFirmwareVersion_CS(IntPtr handle, ref MV_IO_VERSION pstVersion)
        {
            return MV_IO_GetFirmwareVersion(handle, ref pstVersion);
        }

        public static Int32 MV_IO_LocalUpgrade_CS(IntPtr handle, Byte[] pData, uint nLen, ref MV_IO_LOCAL_UPGRADE pstLocalUpgrade)
        {
            return MV_IO_LocalUpgrade(handle, pData, nLen, ref pstLocalUpgrade);
        }

        public static Int32 MV_IO_GetPortInputParam_CS(IntPtr handle, ref MV_IO_SET_INPUT stParam)
        {
            return MV_IO_GetPortInputParam(handle, ref stParam);
        }

        public static Int32 MV_IO_GetPortOutputParam_CS(IntPtr handle, ref MV_IO_SET_OUTPUT stParam)
        {
            return MV_IO_GetPortOutputParam(handle, ref stParam);
        }

        public static Int32 MV_IO_SetInput_CS(IntPtr handle, ref MV_IO_SET_INPUT stParam)
        {
            return MV_IO_SetInput(handle, ref stParam);
        }

        public static Int32 MV_IO_SetOutput_CS(IntPtr handle, ref MV_IO_SET_OUTPUT stParam)
        {
            return MV_IO_SetOutput(handle, ref stParam);
        }

        public static Int32 MV_IO_SetOutputEnable_CS(IntPtr handle, ref MV_IO_OUTPUT_ENABLE stParam)
        {
            return MV_IO_SetOutputEnable(handle, ref stParam);
        }

        public static Int32 MV_IO_GetPNPEnable_CS(IntPtr handle, ref MV_IO_PNP_TYPE nType)
        {
            return MV_IO_GetPNPEnable(handle, ref nType);
        }

        public static Int32 MV_IO_ExcutePNPEnable_CS(IntPtr handle, Int32 nType)
        {
            return MV_IO_ExcutePNPEnable(handle, nType);
        }

        public static Int32 MV_IO_AssociatedOutPort_CS(IntPtr handle, ref MV_IO_ASSOCIATEPORT_PARAM stParam)
        {
            return MV_IO_AssociatedOutPort(handle, ref stParam);
        }

        public static Int32 MV_IO_RegisterEdgeDetectionCallBack_CS(IntPtr handle, cbOutputdelegate cbOutput, IntPtr pUser)
        {
            return MV_IO_RegisterEdgeDetectionCallBack(handle, cbOutput, pUser);
        }

        public static Int32 MV_IO_ResetParam_CS(IntPtr handle)
        {
            return MV_IO_ResetParam(handle);
        }

        public static Int32 MV_IO_Reboot_CS(IntPtr handle)
        {
            return MV_IO_Reboot(handle);
        }

        public static Int32 MV_IO_GetLightParam_CS(IntPtr handle, ref MV_IO_LIGHT_PARAM pstLightParam)
        {
            return MV_IO_GetLightParam(handle, ref pstLightParam);
        }

        public static Int32 MV_IO_Saveparam_CS(IntPtr handle)
        {
            return MV_IO_SaveParam(handle);
        }

        public static Int32 MV_IO_SetLightTriggerSource_CS(IntPtr handle, ref MV_IO_LIGHTSOURCE_PARAM pstSourceParam)
        {
            return MV_IO_SetLightTriggerSource(handle, ref pstSourceParam);
        }

        public static Int32 MV_IO_GetLightTriggerSource_CS(IntPtr handle, ref MV_IO_LIGHTSOURCE_PARAM pstSourceParam)
        {
            return MV_IO_GetLightTriggerSource(handle, ref pstSourceParam);
        }

        public static Int32 MV_IO_SetOutPortTriggerSource_CS(IntPtr handle, ref MV_IO_OUTPUTSOURCE_PARAM pstSourceParam)
        {
            return MV_IO_SetOutPortTriggerSource(handle, ref pstSourceParam);
        }

        public static Int32 MV_IO_GetOutPortTriggerSource_CS(IntPtr handle, ref MV_IO_OUTPUTSOURCE_PARAM pstSourceParam)
        {
            return MV_IO_GetOutPortTriggerSource(handle, ref pstSourceParam);
        }
    }
}