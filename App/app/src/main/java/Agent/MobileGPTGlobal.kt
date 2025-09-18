package Agent

/**
 * MobileGPT全局配置类
 */
class MobileGPTGlobal private constructor() {
    
    companion object {
        // 将此IP地址替换为服务器的IP地址
//        const val HOST_IP = "192.168.100.2"
          const val HOST_IP = "192.168.100.56"
//        const val HOST_IP = "192.168.96.177"
        const val HOST_PORT = 12345
        const val STRING_ACTION = "com.example.MobileGPT.STRING_ACTION"
        const val INSTRUCTION_EXTRA = "com.example.MobileGPT.INSTRUCTION_EXTRA"
        const val APP_NAME_EXTRA = "com.example.MobileGPT.APP_NAME_EXTRA"
        
        /**
         * 可用操作列表
         */
        val AVAILABLE_ACTIONS = listOf("click", "input", "scroll", "long-click", "go-back","go-home")
        
        @Volatile
        private var sInstance: MobileGPTGlobal? = null
        
        /**
         * 获取单例实例
         * @return MobileGPTGlobal实例
         */
        @Synchronized
        fun getInstance(): MobileGPTGlobal {
            return sInstance ?: MobileGPTGlobal().also { sInstance = it }
        }
        
        /**
         * 重置单例实例
         * @return 新的MobileGPTGlobal实例
         */
        @Synchronized
        fun reset(): MobileGPTGlobal {
            sInstance = MobileGPTGlobal()
            return sInstance!!
        }
    }
}