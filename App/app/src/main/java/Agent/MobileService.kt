package Agent

//import Agent.AskPopUp
import android.R
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import android.webkit.WebView
import controller.ElementController
import controller.GenericElement
import controller.NativeController
import controller.PageSniffer
import controller.UIUtils
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.joinToString
import kotlin.isInitialized
import kotlin.jvm.java
import kotlin.jvm.javaClass
import kotlin.let
import kotlin.text.replace
import kotlin.text.startsWith
import kotlin.text.substring

/**
 * MobileGPT普通服务类，负责处理与服务器通信
 */
class MobileService : Service() {
    companion object {
        private const val TAG = "MobileService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MobileGPTServiceChannel"
    }

    private val binder = LocalBinder()
    private lateinit var wm: WindowManager
    private var mClient: MobileGPTClient? = null
    private lateinit var mSpeech: MobileGPTSpeechRecognizer
    private lateinit var agentFloatingWindow: AgentFloatingWindowManager
    private var mMobileGPTGlobal: MobileGPTGlobal? = null
    private var nodeMap: HashMap<Int, GenericElement>? = null
    private var instruction: String? = null
    private var targetPackageName: String? = null
    var xmlPending = false
    var screenNeedUpdate = false
    var firstScreen = false
    private var screenUpdateWaitRunnable: Runnable? = null
    private var screenUpdateTimeoutRunnable: Runnable? = null
    private var clickRetryRunnable: Runnable? = null
    private var actionFailedRunnable: Runnable? = null
    private lateinit var mExecutorService: ExecutorService
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var currentScreenXML = ""
    private var previousScreenXML = ""  // 记录上一次的XML
    private var currentAction = ""      // 记录当前执行的动作
    private var currentInstruction = "" // 记录当前发送的指令
    private var currentScreenShot: Bitmap? = null
    private lateinit var fileDirectory: File
    private var screenUpdateRunnable: Runnable? = null
    private var isScreenUpdateEnabled = false

    // 页面变化监听相关变量
    private var currentViewTreeObserver: ViewTreeObserver? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var currentMonitoredActivity: Activity? = null
    private var lastPageChangeTime = 0L
    private var pageChangeDebounceRunnable: Runnable? = null
    private val PAGE_CHANGE_DEBOUNCE_DELAY = 500L // 防抖延迟500ms
    private var monitoredWebView: WebView? = null
    private var webViewDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var webViewScrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    /**
     * 本地绑定器类
     */
    inner class LocalBinder : Binder() {
        fun getService(): MobileService = this@MobileService
    }

    /**
     * 广播接收器，用于接收指令和答案
     */
    private val stringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MobileGPTGlobal.STRING_ACTION -> {
                    val receivedInstruction = intent.getStringExtra(MobileGPTGlobal.INSTRUCTION_EXTRA)
                    if (receivedInstruction != null) {
                        instruction = receivedInstruction
                        Log.d(TAG, "receive broadcast")
                        mExecutorService.execute { 
                            // 记录当前发送的指令
                            currentInstruction = receivedInstruction
                            Log.d(TAG, "记录当前发送的指令: $currentInstruction")
                            val message = MobileGPTMessage().createInstructionMessage(receivedInstruction)
                            mClient?.sendMessage(message)
                            // 发送指令后启动屏幕更新
                        }
                    } else {
                        Log.e(TAG, "Received null instruction from intent")
                    }
                    // 初始化页面变化的参数
                    xmlPending = true;
                    screenNeedUpdate = true;
                    firstScreen = true;
                    WaitScreenUpdate()
                }
                MobileGPTGlobal.ANSWER_ACTION -> {
                    // 处理答案接收
                    val infoName = intent.getStringExtra(MobileGPTGlobal.INFO_NAME_EXTRA)
                    val question = intent.getStringExtra(MobileGPTGlobal.QUESTION_EXTRA)
                    val answer = intent.getStringExtra(MobileGPTGlobal.ANSWER_EXTRA)
                    val timestamp = intent.getLongExtra("timestamp", 0L)
                    
                    if (infoName != null && question != null && answer != null) {
                        Log.d(TAG, "收到答案: $infoName - $question - $answer (时间戳: $timestamp)")
                        
                        // 验证答案有效性
                        if (answer.isNotBlank()) {
                            // 避免网络请求在主线程：放入执行器
                            mExecutorService.execute {
                                sendAnswer(infoName, question, answer)
                                Log.d(TAG, "答案已发送到服务器")
                            }
                        } else {
                            Log.w(TAG, "收到空答案，忽略")
                        }
                    } else {
                        Log.e(TAG, "收到不完整的答案数据: infoName=$infoName, question=$question, answer=$answer")
                    }
                }
                "com.example.emplab.TRIGGER_PAGE_CHANGE" -> {
                    // 处理页面变化触发广播
                    Log.d(TAG, "收到页面变化触发广播")
                    triggerPageChangeDetection()
                }
            }
        }
    }

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MobileGPT Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MobileGPT Service")
                .setContentText("MobileGPT service is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details) // 使用系统图标
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MobileGPT Service")
                .setContentText("MobileGPT service is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }
    }

    /**
     * 服务绑定时返回IBinder
     */
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    /**
     * 服务创建时的初始化
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MobileService onCreate")
        
        // 创建前台服务通知
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        mExecutorService = Executors.newSingleThreadExecutor()
        
        // 注册广播接收器
        val intentFilter = IntentFilter(MobileGPTGlobal.STRING_ACTION)
        intentFilter.addAction(MobileGPTGlobal.ANSWER_ACTION)
        intentFilter.addAction("com.example.emplab.TRIGGER_PAGE_CHANGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stringReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stringReceiver, intentFilter)
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mSpeech = MobileGPTSpeechRecognizer(this)
        mMobileGPTGlobal = MobileGPTGlobal.getInstance()

        // 延迟初始化网络连接，不阻塞服务启动
        mExecutorService.execute {
            initNetworkConnection()
        }
        screenUpdateWaitRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "screen update waited")
                mainThreadHandler.removeCallbacks(screenUpdateTimeoutRunnable!!)
                // 使用回调确保saveCurrScreen完成后再进行XML比较
                saveCurrScreen {
                    // 比较当前XML和上一个XML是否相同
                    if (isXmlContentSame()) {
                        // XML内容相同，发送错误信息
                        Log.d(TAG, "XML内容相同，发送操作错误信息")
                        sendXmlUnchangedError()
                    } else {
                        // XML内容不同，正常发送屏幕数据
                        Log.d(TAG, "XML内容不同，发送屏幕数据")
                        sendScreen()
                    }
                }
            }
        }

        screenUpdateTimeoutRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "screen update timeout")
                mainThreadHandler.removeCallbacks(screenUpdateWaitRunnable!!)
                // 使用回调确保saveCurrScreen完成后再进行XML比较
                saveCurrScreen {
                    // 比较当前XML和上一个XML是否相同
                    if (isXmlContentSame()) {
                        // XML内容相同，发送错误信息
                        Log.d(TAG, "XML内容相同，发送操作错误信息")
                        sendXmlUnchangedError()
                    } else {
                        // XML内容不同，正常发送屏幕数据
                        Log.d(TAG, "XML内容不同，发送屏幕数据")
                        sendScreen()
                    }
                }
            }
        }



        // 初始化页面变化监听
        initPageChangeListener()

        
        Log.d(TAG, "MobileService 初始化完成")
        
    }

    private fun WaitScreenUpdate(){
        // xmplPending主要为了控制该函数是否需要相应页面变化，例如在showActions时，避免因为弹出悬浮窗导致监听页面变化进而发送XML
        if (xmlPending) {
            if (firstScreen && screenNeedUpdate){
                // for first screen, we wait 5s for loading app;
                Log.d(TAG, "第一次打开应用，设置延迟强制发送");
                screenUpdateTimeoutRunnable?.let {
                    mainThreadHandler.postDelayed(it, 2000)
                }
                screenNeedUpdate = false;

            } else if (!firstScreen) {
                if (screenNeedUpdate){
                    Log.d(TAG, "设置防抖等待发送以及延迟强制发送")
                }
                else{
                    Log.d(TAG, "只设置防抖等待发送")
                }
                if (screenNeedUpdate) {
                    // 取消点击动作的回调
                    clickRetryRunnable?.let {
                        mainThreadHandler.removeCallbacks(it)
                    }
                    //取消进行错误信息的发送（如果不取消，动作执行延迟后后就认为动作失败）
                    actionFailedRunnable?.let {
                        mainThreadHandler.removeCallbacks(it)
                    }
                    screenUpdateTimeoutRunnable?.let {
                        mainThreadHandler.postDelayed(it, 10000)
                    }
                    screenNeedUpdate = false;
                }
                screenUpdateWaitRunnable?.let {
                    mainThreadHandler.removeCallbacks(it)
                    mainThreadHandler.postDelayed(it, 5000)
                }
            }
        }else {
            // 不执行屏幕更新
            LogDedup.d(TAG, "xmlPending为false 不执行屏幕更新")
            // 测试XML的获取
//            saveCurrScreen {
//                Log.d(TAG, "当前屏幕XML: $currentScreenXML")
//            }
            

        }
    }

    /**
     * 初始化页面变化监听
     * 设置Activity变化监听器，当Activity切换时会自动更新ViewTreeObserver监听
     */
    private fun initPageChangeListener() {
        // 设置Activity变化监听器
        ActivityTracker.setActivityChangeListener(object : ActivityTracker.ActivityChangeListener {
            override fun onActivityChanged(newActivity: Activity?, oldActivity: Activity?) {
                Log.d(TAG, "Activity变化: ${oldActivity?.javaClass?.simpleName} -> ${newActivity?.javaClass?.simpleName}")

                // 在主线程中处理Activity变化
                mainThreadHandler.post {
                    handleActivityChange(newActivity, oldActivity)
                }
            }
        })

        // 如果当前已有Activity，立即开始监听
        val currentActivity = ActivityTracker.getCurrentActivity()
        if (currentActivity != null) {
            mainThreadHandler.post {
                setupViewTreeObserver(currentActivity)
            }
        }
    }

    /**
     * 处理Activity变化
     * @param newActivity 新的Activity
     * @param oldActivity 旧的Activity
     */
    private fun handleActivityChange(newActivity: Activity?, oldActivity: Activity?) {
        try {
            // 移除旧Activity的ViewTreeObserver监听
            removeViewTreeObserver()

            // 如果有新Activity，设置新的ViewTreeObserver监听
            if (newActivity != null) {
                setupViewTreeObserver(newActivity)

                // Activity切换时触发页面变化
                onPageChanged("Activity切换: ${oldActivity?.javaClass?.simpleName} -> ${newActivity.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Activity变化时发生异常", e)
        }
    }

    /**
     * 为指定Activity设置ViewTreeObserver监听
     * @param activity 要监听的Activity
     */
    private fun setupViewTreeObserver(activity: Activity) {
        try {
            // 如果已经在监听同一个Activity，不需要重复设置
            if (currentMonitoredActivity == activity && currentViewTreeObserver != null) {
                return
            }

            // 移除旧的监听器
            removeViewTreeObserver()

            val rootView = activity.window?.decorView?.rootView
            if (rootView == null) {
                Log.w(TAG, "无法获取Activity的根视图")
                return
            }

            val viewTreeObserver = rootView.viewTreeObserver
            if (!viewTreeObserver.isAlive) {
                Log.w(TAG, "ViewTreeObserver不可用")
                return
            }

            // 创建全局布局监听器
             globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                 try {
                     // 视图树发生变化时调用
                     Log.d(TAG, "ViewTreeObserver触发 - xmlPending: $xmlPending, screenNeedUpdate: $screenNeedUpdate")
                     onPageChanged("视图树布局变化")
                 } catch (e: Exception) {
                     Log.e(TAG, "处理视图树变化时发生异常", e)
                 }
             }

            // 添加监听器
            viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

            // 如果当前页面为 WebView 页面，额外监听 WebView 的绘制与滚动（DOM 更新不会改变原生视图树）
            try {
                val webView = findFirstWebView(rootView)
                if (webView != null) {
                    val wvObserver = webView.viewTreeObserver
                    if (wvObserver.isAlive) {
                        monitoredWebView = webView
                        // 绘制变化监听（用于捕获 DOM 更新）
                        webViewDrawListener = ViewTreeObserver.OnDrawListener {
                            try {
//                                Log.d(TAG, "WebView绘制变化触发 - 可能是DOM更新")
                                onPageChanged("WebView绘制变化")
                            } catch (e: Exception) {
                                Log.e(TAG, "处理WebView绘制变化时发生异常", e)
                            }
                        }
                        wvObserver.addOnDrawListener(webViewDrawListener)

                        // 滚动变化监听（用户在网页内滚动）
                        webViewScrollListener = ViewTreeObserver.OnScrollChangedListener {
                            try {
//                                Log.d(TAG, "WebView滚动变化触发")
                                onPageChanged("WebView滚动变化")
                            } catch (e: Exception) {
                                Log.e(TAG, "处理WebView滚动变化时发生异常", e)
                            }
                        }
                        wvObserver.addOnScrollChangedListener(webViewScrollListener)

                        Log.d(TAG, "已为Activity ${activity.javaClass.simpleName} 的WebView设置绘制/滚动监听")
                    }
                } else {
                    Log.d(TAG, "未检测到WebView，保持原有视图树监听")
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置WebView监听时发生异常", e)
            }

            // 保存当前监听状态
            currentViewTreeObserver = viewTreeObserver
            currentMonitoredActivity = activity

            Log.d(TAG, "已为Activity ${activity.javaClass.simpleName} 设置ViewTreeObserver监听")

        } catch (e: Exception) {
            Log.e(TAG, "设置ViewTreeObserver监听时发生异常", e)
        }
    }

    /**
     * 移除ViewTreeObserver监听
     */
    private fun removeViewTreeObserver() {
        try {
            if (currentViewTreeObserver != null && globalLayoutListener != null) {
                if (currentViewTreeObserver!!.isAlive) {
                    currentViewTreeObserver!!.removeOnGlobalLayoutListener(globalLayoutListener)
                    Log.d(TAG, "已移除ViewTreeObserver监听")
                }
            }

            // 移除 WebView 的监听
            try {
                val webView = monitoredWebView
                if (webView != null) {
                    val wvObserver = webView.viewTreeObserver
                    if (wvObserver.isAlive) {
                        webViewDrawListener?.let { wvObserver.removeOnDrawListener(it) }
                        webViewScrollListener?.let { wvObserver.removeOnScrollChangedListener(it) }
                        Log.d(TAG, "已移除WebView绘制/滚动监听")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "移除WebView监听时发生异常", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除ViewTreeObserver监听时发生异常", e)
        } finally {
            currentViewTreeObserver = null
            globalLayoutListener = null
            currentMonitoredActivity = null
            monitoredWebView = null
            webViewDrawListener = null
            webViewScrollListener = null
        }
    }

    /**
     * 页面变化处理方法
     * 当检测到页面变化时调用WaitScreenUpdte方法
     * @param reason 变化原因
     */
    private fun onPageChanged(reason: String) {
        val currentTime = System.currentTimeMillis()
        LogDedup.d(TAG, "处理页面变化: $reason")
        WaitScreenUpdate()
    }

    /**
     * 在视图树中查找第一个WebView
     */
    private fun findFirstWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findFirstWebView(child)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * 手动触发页面变化检测
     * 可供外部调用，强制检测当前页面状态
     */
    fun triggerPageChangeDetection() {
        Log.d(TAG, "手动触发页面变化检测")
        onPageChanged("手动触发检测")
    }

    /**
     * 获取当前页面变化监听状态
     * @return 是否正在监听页面变化
     */
    fun isPageChangeListenerActive(): Boolean {
        return currentViewTreeObserver != null &&
               globalLayoutListener != null &&
               currentMonitoredActivity != null
    }

    /**
     * 服务启动时调用
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 发送回答
     */
    fun sendAnswer(infoName: String, question: String, answer: String) {
        val qaString = "$infoName\\$question\\$answer"
        val message = MobileGPTMessage().createQAMessage(qaString)
        mClient?.sendMessage(message)
    }
    
    /**
     * 显示悬浮窗
     */
    fun showFloatingWindow() {
        if (::agentFloatingWindow.isInitialized) {
            agentFloatingWindow.showFloatingWindow()
        }
    }
    
    /**
     * 隐藏悬浮窗
     */
    fun hideFloatingWindow() {
        if (::agentFloatingWindow.isInitialized) {
            agentFloatingWindow.hideFloatingWindow()
        }
    }
    
    /**
     * 切换悬浮窗显示状态
     */
    fun toggleFloatingWindow() {
        if (::agentFloatingWindow.isInitialized) {
            agentFloatingWindow.toggleFloatingWindow()
        }
    }

    /**
     * 处理服务器响应
     */
    @SuppressLint("DefaultLocale")
    private fun handleResponse(message: String) {
        var actionSuccess = true
        Log.d(TAG, "Received message: $message")

        // 选择应用
        if (message.startsWith("##$$##")) {
            val selectedApp = message.substring(6)
            targetPackageName = selectedApp
            fileDirectory = File(getExternalFilesDir(null), targetPackageName)
            if (!fileDirectory.exists()) {
                fileDirectory.mkdirs()
            }
            return
        } else if (message.startsWith("$$##$$")) {
            val subtask = message.substring(6)
            return
        } else if (message.startsWith("$$$$$")) {
            // 断开服务器连接
            Log.d(TAG, "-----------Task finished--------")
            mSpeech.speak("任务已完成。", false)
            reset()
            return
        }

        try {
            val gptMessage = GPTMessage(message)
            val action = gptMessage.getActionName()
            val args = gptMessage.getArgs()
            
            // 记录当前执行的动作
            currentAction = action
            Log.d(TAG, "记录当前执行的动作: $action")

            when (action) {
                "speak" -> {
                    val content = args.get("message") as String
                    mSpeech.speak(content, false)
                    return
                }
                "ask" -> {
                    val question = args.get("question") as String
                    val infoName = args.get("info_name") as String
                    handleAsk(infoName, question)
                }
                in MobileGPTGlobal.AVAILABLE_ACTIONS -> {
                    // 执行UI动作
                    executeUIAction(action, args)


                    // 执行完动作后，修改进行屏幕发送的变量。设置运行失败的Runnable
                    screenNeedUpdate = true;
                    xmlPending = true;
                    setActionFailedRunnable("There is no change in the screen. Try other approach.", 10000);

                }
            }
        } catch (e: JSONException) {
            val error = "The action has wrong parameters. Make sure you have put all parameters correctly."
            e.printStackTrace()
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_ERROR
                errType = MobileGPTMessage.ERROR_TYPE_ACTION
                errMessage = error
                curXml = currentScreenXML    // 包含当前的XML
                preXml = previousScreenXML   // 包含上一次的XML
                action = currentAction       // 包含当前执行的动作
                instruction = currentInstruction // 包含当前发送的指令
            }
            mExecutorService.execute { mClient?.sendMessage(message) }
            Log.e(TAG, "wrong json format")
        }
    }

    /**
     * 处理问题
     */
    private fun handleAsk(info: String, question: String) {
        Log.d(TAG, "Asking question: $question")
        mSpeech.speak(question, true)
        // 在当前前台Activity中显示应用内弹窗
        val activity = ActivityTracker.getCurrentActivity()
        if (activity != null) {
            activity.runOnUiThread {
                try {
                    AgentFloatingWindowManager(activity).showAskDialog(info, question)
                } catch (e: Exception) {
                    Log.e(TAG, "显示Ask对话框失败: ${e.message}")
                }
            }
        } else {
            Log.e(TAG, "当前没有前台Activity，无法显示Ask对话框")
        }
    }

    /**
     * 执行UI动作
     * @param action 动作名称
     * @param args 动作参数
     */
    private fun executeUIAction(action: String, args: org.json.JSONObject) {
        try {
            // 获取当前Activity
            val currentActivity = ActivityTracker.getCurrentActivity()
            if (currentActivity == null) {
                Log.e(TAG, "当前Activity为空，无法执行UI动作")
                sendActionError("当前Activity为空，无法执行UI动作")
                return
            }

            // 获取目标元素的index
            val index = if (args.has("index")) {
                try {
                    args.getInt("index")
                } catch (e: Exception) {
                    args.getString("index").toInt()
                }
            } else {
                Log.e(TAG, "动作参数中缺少index")
                sendActionError("动作参数中缺少index")
                return
            }

            // 从nodeMap中获取目标元素
            val targetElement = nodeMap?.get(index)
            if (targetElement == null) {
                Log.e(TAG, "未找到index为${index}的元素")
                sendActionError("未找到index为${index}的元素")
                return
            }

            Log.d(TAG, "执行动作: $action, 目标元素: ${targetElement.resourceId}, index: $index")

            // 根据动作类型执行相应操作
            when (action) {
                "click" -> {
                    executeClickAction(currentActivity, targetElement)
                }
                "input" -> {
                    val inputText = args.optString("input_text", "")
                    executeInputAction(currentActivity, targetElement, inputText)
                }
                "scroll" -> {
                    val direction = args.optString("direction", "down")
                    executeScrollAction(currentActivity, targetElement, direction)
                }
                "long-click" -> {
                    executeLongClickAction(currentActivity, targetElement)
                }
                "go-back" -> {
                    executeGoBackAction(currentActivity)
                }
                "go-home" -> {
                    executeGoHomeAction(currentActivity)
                }
                else -> {
                    Log.e(TAG, "不支持的动作类型: $action")
                    sendActionError("不支持的动作类型: $action")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "执行UI动作时发生异常", e)
            sendActionError("执行UI动作时发生异常: ${e.message}")
        }
    }

    /**
     * 执行点击动作
     * 优先使用GenericElement中的view引用进行直接点击，提高点击成功率和性能
     * 利用现有的页面变化监听机制来判断点击是否成功
     */
    private fun executeClickAction(activity: Activity, element: GenericElement) {
        Log.d(TAG, "开始执行点击动作 - 元素: ${element.resourceId}, clickable: ${element.clickable}, enabled: ${element.enabled}")
        
        // 如果当前页面为 WebView，直接使用坐标点击，提高在网页中的命中率
        try {
            val pageType = PageSniffer.getCurrentPageType(activity)
            if (pageType == PageSniffer.PageType.WEB_VIEW) {
                Log.d(TAG, "检测到页面类型为 WEB_VIEW，直接使用坐标点击")
                executeCoordinateClick(activity, element)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "页面类型检测失败，继续使用默认点击流程", e)
        }

        // 记录点击前的状态
        val preClickActivity = ActivityTracker.getCurrentActivity()
        val preClickActivityName = preClickActivity?.javaClass?.simpleName ?: "null"
        val preClickMonitoredActivity = currentMonitoredActivity
        var preClickViewTreeHash: Int? = null
        
        // 获取点击前页面元素树的哈希值
        preClickActivity?.let { clickActivity ->
            try {
                val rootView = clickActivity.window?.decorView?.rootView
                preClickViewTreeHash = if (rootView != null) {
                    getViewTreeHash(rootView)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取点击前视图树哈希值失败", e)
            }
        }
        
        Log.d(TAG, "记录点击前状态 - Activity: $preClickActivityName, 监听Activity: ${preClickMonitoredActivity?.javaClass?.simpleName}, 视图树哈希: $preClickViewTreeHash")

        
        
        // 首先检查目标元素是否可点击
        if (element.clickable && element.enabled) {
            // 优先使用view引用进行直接点击
            if (element.view != null) {
                Log.d(TAG, "使用view引用进行直接点击")
                ElementController.clickElementByView(element) { success ->
                    if (success) {
                        Log.d(TAG, "view引用点击操作返回成功，等待页面变化验证...")
                        screenNeedUpdate = true
                        xmlPending = true
                        // 利用现有的页面变化监听机制来验证点击效果
                        verifyClickSuccessWithPageChange(preClickActivity, preClickMonitoredActivity, preClickViewTreeHash) { verified ->
                            if (verified) {
                                Log.d(TAG, "通过view引用点击成功且已验证生效")
                            } else {
                                Log.w(TAG, "view引用点击操作成功但未生效，回退到传统方式")
                                fallbackClickAction(activity, element, preClickActivity, preClickMonitoredActivity, preClickViewTreeHash)
                            }
                        }
                    } else {
                        Log.w(TAG, "通过view引用点击失败，回退到传统方式")
                        fallbackClickAction(activity, element, preClickActivity, preClickMonitoredActivity, preClickViewTreeHash)
                    }
                }
            } else {
                Log.d(TAG, "元素没有view引用，使用传统直接点击方式")
                // 没有view引用时使用传统方式
                ElementController.clickElement(activity, element.resourceId) { success ->
                    if (success) {
                        screenNeedUpdate = true
                        xmlPending = true
                        Log.d(TAG, "传统点击操作返回成功，等待页面变化验证...")
                        // 利用现有的页面变化监听机制来验证点击效果
                        verifyClickSuccessWithPageChange(preClickActivity, preClickMonitoredActivity, preClickViewTreeHash) { verified ->
                            if (verified) {
                                Log.d(TAG, "传统点击成功且已验证生效")
                            } else {
                                Log.w(TAG, "传统点击操作成功但未生效，回退到坐标点击")
                                executeCoordinateClick(activity, element)
                            }
                        }
                    } else {
                        Log.w(TAG, "传统点击动作执行失败，回退到坐标点击")
                        executeCoordinateClick(activity, element)
                    }
                }
            }
        } else {
            Log.w(TAG, "目标元素不可点击或未启用，直接使用坐标点击")
            executeCoordinateClick(activity, element)
        }
    }

    /**
     * 传统的点击操作回退方法
     * 利用页面变化监听机制验证点击效果
     * @param activity 当前Activity
     * @param element 目标元素
     * @param preClickActivity 点击前的Activity
     * @param preClickMonitoredActivity 点击前监听的Activity
     * @param preClickViewTreeHash 点击前页面元素树的哈希值
     */
    private fun fallbackClickAction(
        activity: Activity, 
        element: GenericElement,
        preClickActivity: Activity?,
        preClickMonitoredActivity: Activity?,
        preClickViewTreeHash: Int?
    ) {
        // 首先检查目标元素是否可点击
        if (element.clickable && element.enabled) {
            // 目标元素可点击，直接执行
            ElementController.clickElement(activity, element.resourceId) { success ->
                if (success) {
                    Log.d(TAG, "传统回退点击操作返回成功，等待页面变化验证...")
                    screenNeedUpdate = true
                    xmlPending = true
                    // 利用现有的页面变化监听机制来验证点击效果
                    verifyClickSuccessWithPageChange(preClickActivity, preClickMonitoredActivity, preClickViewTreeHash) { verified ->
                        if (verified) {
                            Log.d(TAG, "传统回退点击成功且已验证生效")
                        } else {
                            Log.w(TAG, "传统回退点击操作成功但未生效，使用坐标点击")
                            executeCoordinateClick(activity, element)
                        }
                    }
                } else {
                    Log.w(TAG, "传统回退点击动作执行失败，使用坐标点击")
                    executeCoordinateClick(activity, element)
                }
            }
        } else {
            Log.d(TAG, "目标元素不可直接点击，使用坐标点击")
            executeCoordinateClick(activity, element)
        }
    }

    /**
     * 执行坐标点击操作
     */
    private fun executeCoordinateClick(activity: Activity, element: GenericElement) {
        Log.d(TAG, "执行坐标点击操作")
        clickByCoordinateDP(activity, element) { success ->
            if (success) {
                Log.d(TAG, "坐标点击成功")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "坐标点击失败")
                sendActionError("所有点击方式都失败了")
            }
        }
    }

    /**
     * 利用页面变化监听机制验证点击效果
     * 通过监听Activity变化和页面元素树变化来判断点击是否生效
     * @param preClickActivity 点击前的Activity
     * @param preClickMonitoredActivity 点击前监听的Activity
     * @param preClickViewTreeHash 点击前页面元素树的哈希值
     * @param callback 验证结果回调
     */
    private fun verifyClickSuccessWithPageChange(
        preClickActivity: Activity?,
        preClickMonitoredActivity: Activity?,
        preClickViewTreeHash: Int?,
        callback: (Boolean) -> Unit
    ) {
        var verificationCompleted = false
        val startTime = System.currentTimeMillis()
        
        // 使用传入的点击前状态
        val initialActivity = preClickActivity
        val initialActivityName = initialActivity?.javaClass?.simpleName ?: "null"
        val initialMonitoredActivity = preClickMonitoredActivity
        val initialViewTreeHash = preClickViewTreeHash
        
        Log.d(TAG, "开始页面变化验证 - 点击前状态: Activity=$initialActivityName, 监听Activity=${initialMonitoredActivity?.javaClass?.simpleName}, 视图树哈希=${initialViewTreeHash}")
        
        // 创建一个检查器，定期检查页面状态变化
        val checkPageChangeRunnable = object : Runnable {
            override fun run() {
                if (verificationCompleted) return
                
                val currentTime = System.currentTimeMillis()
                val elapsed = currentTime - startTime
                val currentActivity = ActivityTracker.getCurrentActivity()
                val currentActivityName = currentActivity?.javaClass?.simpleName ?: "null"
                
                // 检查Activity变化
                val hasActivityChange = currentActivity != initialActivity
                
                // 检查监听Activity变化
                val hasMonitoredActivityChange = currentMonitoredActivity != initialMonitoredActivity
                
                // 检查页面元素树变化
                var hasViewTreeChange = false
                if (initialViewTreeHash != null && currentActivity != null) {
                    try {
                        val rootView = currentActivity.window?.decorView?.rootView
                        if (rootView != null) {
                            val currentViewTreeHash = getViewTreeHash(rootView)
                            hasViewTreeChange = currentViewTreeHash != initialViewTreeHash
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "检查视图树变化时发生异常", e)
                    }
                }
                
                // 综合判断是否有页面变化
                val hasPageChange = hasActivityChange || hasMonitoredActivityChange || hasViewTreeChange
                
                if (hasPageChange) {
                    verificationCompleted = true
                    
                    val changeReason = when {
                        hasActivityChange -> "Activity变化 ($initialActivityName -> $currentActivityName)"
                        hasMonitoredActivityChange -> "监听Activity变化 (${initialMonitoredActivity?.javaClass?.simpleName} -> ${currentMonitoredActivity?.javaClass?.simpleName})"
                        hasViewTreeChange -> "页面元素树变化"
                        else -> "未知变化"
                    }
                    
                    Log.d(TAG, "检测到页面变化，点击验证成功 - 变化原因: $changeReason")
                    callback(true)
                } else if (elapsed >= 1000) {
                    // 超时，认为点击未生效
                    verificationCompleted = true
                    
                    Log.d(TAG, "点击验证超时，未检测到页面变化 - 当前状态: Activity=$currentActivityName, 监听Activity=${currentMonitoredActivity?.javaClass?.simpleName}")
                    callback(false)
                } else {
                    // 继续检查
                    mainThreadHandler.postDelayed(this, 100) // 每150ms检查一次，提高响应速度
                }
            }
        }
        
        // 开始检查
        mainThreadHandler.postDelayed(checkPageChangeRunnable, 100) // 首次检查延迟100ms
    }

    /**
     * 获取当前屏幕状态的哈希值
     * 用于判断屏幕是否发生变化
     */
    private fun getCurrentScreenHash(activity: Activity): String {
        return try {
            val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
            val viewTreeHash = getViewTreeHash(rootView)
            viewTreeHash.toString()
        } catch (e: Exception) {
            Log.w(TAG, "获取屏幕哈希失败: ${e.message}")
            System.currentTimeMillis().toString()
        }
    }

    /**
     * 递归计算视图树的哈希值
     */
    private fun getViewTreeHash(view: View): Int {
        var hash = view.javaClass.simpleName.hashCode()
        hash = hash * 31 + view.visibility
        hash = hash * 31 + view.isEnabled.hashCode()
        
        if (view is TextView) {
            hash = hash * 31 + (view.text?.toString()?.hashCode() ?: 0)
        }
        
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hash = hash * 31 + getViewTreeHash(view.getChildAt(i))
            }
        }
        
        return hash
    }

    /**
     * 执行输入动作
     */
    private fun executeInputAction(activity: Activity, element: GenericElement, inputText: String) {
        ElementController.setInputValue(activity, element.resourceId, inputText) { success ->
            if (success) {
                Log.d(TAG, "输入动作执行成功: $inputText")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "输入动作执行失败")
                sendActionError("输入动作执行失败")
            }
        }
    }

    /**
     * 执行滚动动作
     */
    private fun executeScrollAction(activity: Activity, element: GenericElement, direction: String) {
        // 使用NativeController的滚动功能
        val startX = element.bounds.centerX().toFloat()
        val startY = element.bounds.centerY().toFloat()
        val endX = startX
        val endY = when (direction.lowercase()) {
            "up" -> startY - 200
            "down" -> startY + 200
            "left" -> startX - 200
            "right" -> startX + 200
            else -> startY + 200
        }

        controller.NativeController.scrollByTouch(activity, startX, startY, endX, endY) { success ->
            if (success) {
                Log.d(TAG, "滚动动作执行成功: $direction")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "滚动动作执行失败")
                sendActionError("滚动动作执行失败")
            }
        }
    }

    /**
     * 执行长按动作
     */
    /**
     * 执行长按动作
     * 参考点击操作的逻辑和方法，包括成功验证机制、三种长按形式和页面验证机制
     * 优先使用GenericElement中的view引用进行直接长按，提高长按成功率和性能
     * 利用现有的页面变化监听机制来判断长按是否成功
     */
    private fun executeLongClickAction(activity: Activity, element: GenericElement) {
        Log.d(TAG, "开始执行长按动作 - 元素: ${element.resourceId}, longClickable: ${element.longClickable}, enabled: ${element.enabled}")
        
        // 记录长按前的状态
        val preLongClickActivity = ActivityTracker.getCurrentActivity()
        val preLongClickActivityName = preLongClickActivity?.javaClass?.simpleName ?: "null"
        val preLongClickMonitoredActivity = currentMonitoredActivity
        var preLongClickViewTreeHash: Int? = null
        
        // 获取长按前页面元素树的哈希值
        preLongClickActivity?.let { longClickActivity ->
            try {
                val rootView = longClickActivity.window?.decorView?.rootView
                preLongClickViewTreeHash = if (rootView != null) {
                    getViewTreeHash(rootView)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取长按前视图树哈希值失败", e)
            }
        }
        
        Log.d(TAG, "记录长按前状态 - Activity: $preLongClickActivityName, 监听Activity: ${preLongClickMonitoredActivity?.javaClass?.simpleName}, 视图树哈希: $preLongClickViewTreeHash")
        
        // 首先检查目标元素是否可长按
        if (element.longClickable && element.enabled) {
            // 优先使用view引用进行直接长按
            if (element.view != null) {
                Log.d(TAG, "使用view引用进行直接长按")
                ElementController.longClickElementByView(element) { success ->
                    if (success) {
                        Log.d(TAG, "view引用长按操作返回成功，等待页面变化验证...")
                        screenNeedUpdate = true
                        xmlPending = true
                        // 利用现有的页面变化监听机制来验证长按效果
                        verifyLongClickSuccessWithPageChange(preLongClickActivity, preLongClickMonitoredActivity, preLongClickViewTreeHash) { verified ->
                            if (verified) {
                                Log.d(TAG, "通过view引用长按成功且已验证生效")
                            } else {
                                Log.w(TAG, "view引用长按操作成功但未生效，回退到传统方式")
                                fallbackLongClickAction(activity, element, preLongClickActivity, preLongClickMonitoredActivity, preLongClickViewTreeHash)
                            }
                        }
                    } else {
                        Log.w(TAG, "通过view引用长按失败，回退到传统方式")
                        fallbackLongClickAction(activity, element, preLongClickActivity, preLongClickMonitoredActivity, preLongClickViewTreeHash)
                    }
                }
            } else {
                Log.d(TAG, "元素没有view引用，使用传统直接长按方式")
                // 没有view引用时使用传统方式
                ElementController.longClickElement(activity, element.resourceId) { success ->
                    if (success) {
                        screenNeedUpdate = true
                        xmlPending = true
                        Log.d(TAG, "传统长按操作返回成功，等待页面变化验证...")
                        // 利用现有的页面变化监听机制来验证长按效果
                        verifyLongClickSuccessWithPageChange(preLongClickActivity, preLongClickMonitoredActivity, preLongClickViewTreeHash) { verified ->
                            if (verified) {
                                Log.d(TAG, "传统长按成功且已验证生效")
                            } else {
                                Log.w(TAG, "传统长按操作成功但未生效，回退到坐标长按")
                                executeCoordinateLongClick(activity, element)
                            }
                        }
                    } else {
                        Log.w(TAG, "传统长按动作执行失败，回退到坐标长按")
                        executeCoordinateLongClick(activity, element)
                    }
                }
            }
        } else {
            Log.w(TAG, "目标元素不可长按或未启用，直接使用坐标长按")
            executeCoordinateLongClick(activity, element)
        }
    }

    /**
     * 传统的长按操作回退方法
     * 利用页面变化监听机制验证长按效果
     * @param activity 当前Activity
     * @param element 目标元素
     * @param preLongClickActivity 长按前的Activity
     * @param preLongClickMonitoredActivity 长按前监听的Activity
     * @param preLongClickViewTreeHash 长按前页面元素树的哈希值
     */
    private fun fallbackLongClickAction(
        activity: Activity, 
        element: GenericElement,
        preLongClickActivity: Activity?,
        preLongClickMonitoredActivity: Activity?,
        preLongClickViewTreeHash: Int?
    ) {
        Log.d(TAG, "执行传统长按回退操作")
        ElementController.longClickElement(activity, element.resourceId) { success ->
            if (success) {
                screenNeedUpdate = true
                xmlPending = true
                Log.d(TAG, "传统长按回退操作返回成功，等待页面变化验证...")
                verifyLongClickSuccessWithPageChange(preLongClickActivity, preLongClickMonitoredActivity, preLongClickViewTreeHash) { verified ->
                    if (verified) {
                        Log.d(TAG, "传统长按回退成功且已验证生效")
                    } else {
                        Log.w(TAG, "传统长按回退操作成功但未生效，回退到坐标长按")
                        executeCoordinateLongClick(activity, element)
                    }
                }
            } else {
                Log.w(TAG, "传统长按回退操作失败，回退到坐标长按")
                executeCoordinateLongClick(activity, element)
            }
        }
    }

    /**
     * 执行坐标长按操作
     * @param activity 当前Activity
     * @param element 目标元素
     */
    private fun executeCoordinateLongClick(activity: Activity, element: GenericElement) {
        Log.d(TAG, "执行坐标长按操作 - 元素: ${element.resourceId}")
        longClickByCoordinateDP(activity, element) { success ->
            if (success) {
                Log.d(TAG, "坐标长按操作成功")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "坐标长按操作失败")
                sendActionError("长按动作执行失败", "所有长按方式均失败")
            }
        }
    }

    /**
     * 验证长按操作是否成功，通过检测页面变化
     * 复用点击操作的页面验证机制
     * @param preLongClickActivity 长按前的Activity
     * @param preLongClickMonitoredActivity 长按前监听的Activity
     * @param preLongClickViewTreeHash 长按前页面元素树的哈希值
     * @param callback 验证结果回调
     */
    private fun verifyLongClickSuccessWithPageChange(
        preLongClickActivity: Activity?,
        preLongClickMonitoredActivity: Activity?,
        preLongClickViewTreeHash: Int?,
        callback: (Boolean) -> Unit
    ) {
        var verificationCompleted = false
        val startTime = System.currentTimeMillis()
        
        // 使用传入的长按前状态
        val initialActivity = preLongClickActivity
        val initialActivityName = initialActivity?.javaClass?.simpleName ?: "null"
        val initialMonitoredActivity = preLongClickMonitoredActivity
        val initialViewTreeHash = preLongClickViewTreeHash
        
        Log.d(TAG, "开始长按页面变化验证 - 长按前状态: Activity=$initialActivityName, 监听Activity=${initialMonitoredActivity?.javaClass?.simpleName}, 视图树哈希=${initialViewTreeHash}")
        
        // 创建一个检查器，定期检查页面状态变化
        val checkPageChangeRunnable = object : Runnable {
            override fun run() {
                if (verificationCompleted) return
                
                val currentTime = System.currentTimeMillis()
                val elapsed = currentTime - startTime
                val currentActivity = ActivityTracker.getCurrentActivity()
                val currentActivityName = currentActivity?.javaClass?.simpleName ?: "null"
                
                // 检查Activity变化
                val hasActivityChange = currentActivity != initialActivity
                
                // 检查监听Activity变化
                val hasMonitoredActivityChange = currentMonitoredActivity != initialMonitoredActivity
                
                // 检查页面元素树变化
                var hasViewTreeChange = false
                if (initialViewTreeHash != null && currentActivity != null) {
                    try {
                        val rootView = currentActivity.window?.decorView?.rootView
                        if (rootView != null) {
                            val currentViewTreeHash = getViewTreeHash(rootView)
                            hasViewTreeChange = currentViewTreeHash != initialViewTreeHash
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "检查长按视图树变化时发生异常", e)
                    }
                }
                
                // 综合判断是否有页面变化
                val hasPageChange = hasActivityChange || hasMonitoredActivityChange || hasViewTreeChange
                
                if (hasPageChange) {
                    verificationCompleted = true
                    
                    val changeReason = when {
                        hasActivityChange -> "Activity变化 ($initialActivityName -> $currentActivityName)"
                        hasMonitoredActivityChange -> "监听Activity变化 (${initialMonitoredActivity?.javaClass?.simpleName} -> ${currentMonitoredActivity?.javaClass?.simpleName})"
                        hasViewTreeChange -> "页面元素树变化"
                        else -> "未知变化"
                    }
                    
                    Log.d(TAG, "检测到页面变化，长按验证成功 - 变化原因: $changeReason")
                    callback(true)
                } else if (elapsed >= 1000) {
                    // 超时，认为长按未生效
                    verificationCompleted = true
                    
                    Log.d(TAG, "长按验证超时，未检测到页面变化 - 当前状态: Activity=$currentActivityName, 监听Activity=${currentMonitoredActivity?.javaClass?.simpleName}")
                    callback(false)
                } else {
                    // 继续检查
                    mainThreadHandler.postDelayed(this, 100) // 每100ms检查一次，提高响应速度
                }
            }
        }
        
        // 开始检查
        mainThreadHandler.postDelayed(checkPageChangeRunnable, 100) // 首次检查延迟100ms
    }

    /**
     * 通过dp坐标执行长按操作
     * @param activity 当前Activity
     * @param targetElement 目标元素
     * @param callback 操作结果回调
     */
    private fun longClickByCoordinateDP(activity: Activity, targetElement: GenericElement, callback: (Boolean) -> Unit) {
        Log.d(TAG, "开始通过dp坐标执行长按操作")
        
        try {
            val bounds = targetElement.bounds
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            
            Log.d(TAG, "长按坐标: ($centerX, $centerY)")
            
            // 根据页面类型调用相应的长按方法
            val currentActivity = ActivityTracker.getCurrentActivity()
            if (currentActivity != null) {
                ElementController.longClickByCoordinateDp(activity, centerX.toFloat(), centerY.toFloat()) { success ->
                    Log.d(TAG, "dp坐标长按操作结果: $success")
                    callback(success)
                }
            } else {
                Log.e(TAG, "无法获取当前Activity，长按操作失败")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dp坐标长按操作异常", e)
            callback(false)
        }
    }

    /**
     * 执行后退动作
     */
    private fun executeGoBackAction(activity: Activity) {
        controller.NativeController.goBack(activity) { success ->
            if (success) {
                Log.d(TAG, "后退动作执行成功")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "后退动作执行失败")
                sendActionError("后退动作执行失败")
            }
        }
    }

    /**
     * 执行回到主页动作
     */
    private fun executeGoHomeAction(activity: Activity,) {
        controller.NativeController.goToAppHome(activity) { success ->
            if (success) {
                Log.d(TAG, "回到主页动作执行成功")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "回到主页动作执行失败")
                sendActionError("回到主页动作执行失败")
            }
        }
    }

    /**
     * 发送动作错误信息（带最新截图）
     */
    private fun sendActionError(errorMessage: String, remark: String = "") {
        // 先强制更新截图，然后发送错误信息
        saveCurrentScreenShot {
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_ERROR
                errType = MobileGPTMessage.ERROR_TYPE_ACTION
                errMessage = errorMessage
                curXml = currentScreenXML    // 包含当前的XML
                preXml = previousScreenXML   // 包含上一次的XML
                action = currentAction       // 包含当前执行的动作
                instruction = currentInstruction // 包含当前发送的指令
                this.remark = remark
                // 添加最新更新的截图
                screenshot = currentScreenShot
            }
            mExecutorService.execute { mClient?.sendMessage(message) }
        }
    }
    
    /**
     * 查找最近的可点击节点
     */
    private fun findNearestClickableNode(activity: Activity, targetElement: GenericElement, callback: (GenericElement?) -> Unit) {
        // 获取当前元素树
        ElementController.getCurrentElementTree(activity) { elementTree ->
            // 查找最近的可点击节点
            val clickableElement = findNearestClickableNodeInTree(elementTree, targetElement)
            callback(clickableElement)
        }
    }
    
    /**
     * 在元素树中查找最近的可点击节点
     */
    private fun findNearestClickableNodeInTree(rootElement: GenericElement, targetElement: GenericElement): GenericElement? {
        var nearestClickable: GenericElement? = null
        var minDistance = Float.MAX_VALUE
        
        // 遍历整个元素树
        traverseElementTree(rootElement) { element ->
            if (element.clickable && element.enabled) {
                val distance = calculateDistance(element.bounds, targetElement.bounds)
                if (distance < minDistance) {
                    minDistance = distance
                    nearestClickable = element
                }
            }
        }
        
        return nearestClickable
    }
    
    /**
     * 遍历元素树
     */
    private fun traverseElementTree(element: GenericElement, action: (GenericElement) -> Unit) {
        action(element)
        for (child in element.children) {
            traverseElementTree(child, action)
        }
    }
    
    /**
     * 计算两个边界矩形之间的距离
     * 优先考虑包含关系和重叠面积
     */
    private fun calculateDistance(targetBounds: Rect, clickableBounds: Rect): Float {
        // 1. 如果可点击元素包含目标元素，距离为0（最高优先级）
        if (clickableBounds.contains(targetBounds)) {
            return 0f
        }
        
        // 2. 计算重叠面积
        val overlapArea = calculateOverlapArea(targetBounds, clickableBounds)
        if (overlapArea > 0) {
            // 有重叠，返回负的重叠面积（重叠面积越大，距离越小）
            return -overlapArea
        }
        
        // 3. 没有重叠，计算边界之间的最小距离
        val dx: Float = when {
            targetBounds.right < clickableBounds.left -> (clickableBounds.left - targetBounds.right).toFloat()
            targetBounds.left > clickableBounds.right -> (targetBounds.left - clickableBounds.right).toFloat()
            else -> 0f
        }
        
        val dy: Float = when {
            targetBounds.bottom < clickableBounds.top -> (clickableBounds.top - targetBounds.bottom).toFloat()
            targetBounds.top > clickableBounds.bottom -> (targetBounds.top - clickableBounds.bottom).toFloat()
            else -> 0f
        }
        
        return kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
    }
    
    /**
     * 计算两个矩形的重叠面积
     */
    private fun calculateOverlapArea(rect1: Rect, rect2: Rect): Float {
        val left = kotlin.math.max(rect1.left, rect2.left)
        val top = kotlin.math.max(rect1.top, rect2.top)
        val right = kotlin.math.min(rect1.right, rect2.right)
        val bottom = kotlin.math.min(rect1.bottom, rect2.bottom)
        
        if (left < right && top < bottom) {
            return (right - left) * (bottom - top).toFloat()
        }
        return 0f
    }
    
    /**
     * 构建可点击节点的remark信息
     */
    private fun buildClickableNodeRemark(targetElement: GenericElement, clickableElement: GenericElement, success: Boolean): String {
        val status = if (success) "成功" else "失败"
        val distance = calculateDistance(targetElement.bounds, clickableElement.bounds)
        
        return buildString {
            append("目标元素不可点击，已尝试点击最近的可点击节点但${status}。")
            append("目标元素: resourceId='${targetElement.resourceId}', ")
            append("className='${targetElement.className}', ")
            append("text='${targetElement.text}', ")
            append("bounds=[${targetElement.bounds.left},${targetElement.bounds.top},${targetElement.bounds.right},${targetElement.bounds.bottom}]。")
            append("实际点击元素: resourceId='${clickableElement.resourceId}', ")
            append("className='${clickableElement.className}', ")
            append("text='${clickableElement.text}', ")
            append("bounds=[${clickableElement.bounds.left},${clickableElement.bounds.top},${clickableElement.bounds.right},${clickableElement.bounds.bottom}]。")
            append("距离: ${String.format("%.1f", distance)}px")
        }
    }
    
    /**
     * 构建未找到可点击节点的remark信息
     */
    private fun buildNoClickableNodeRemark(targetElement: GenericElement): String {
        return buildString {
            append("目标元素不可点击，且未找到任何可点击的节点。")
            append("目标元素: resourceId='${targetElement.resourceId}', ")
            append("className='${targetElement.className}', ")
            append("text='${targetElement.text}', ")
            append("bounds=[${targetElement.bounds.left},${targetElement.bounds.top},${targetElement.bounds.right},${targetElement.bounds.bottom}]。")
            append("clickable=${targetElement.clickable}, enabled=${targetElement.enabled}")
        }
    }
    
    /**
     * 检查元素是否是可滚动的容器
     */
    private fun isScrollableContainer(element: GenericElement): Boolean {
        return element.scrollable || 
               element.className.contains("ListView") || 
               element.className.contains("RecyclerView") ||
               element.className.contains("ScrollView") ||
               element.className.contains("NestedScrollView")
    }

    /**
     * 使用坐标点击目标元素
     */
    private fun clickByCoordinateDP(activity: Activity, targetElement: GenericElement, callback: (Boolean) -> Unit) {
        // 计算目标元素的中心坐标
        val centerX = (targetElement.bounds.left + targetElement.bounds.right)/2f
        val centerY = (targetElement.bounds.top + targetElement.bounds.bottom)/2f
//        val centerX = targetElement.bounds.left
//        val centerY = targetElement.bounds.top
        
        Log.d(TAG, "使用坐标点击 (dp): ($centerX dp, $centerY dp)")
        
        // 使用ElementController的坐标点击功能
        ElementController.clickByCoordinateDp(activity, centerX.toFloat(), centerY.toFloat()) { success ->
            callback(success)
        }
//        NativeController.clickByCoordinateDp(activity, centerX.toFloat(), centerY.toFloat()) { success ->
//            callback(success)
//        }
    }


    /**
     * 保存当前屏幕信息
     * @param onComplete 所有异步操作完成后的回调
     */
    private fun saveCurrScreen(onComplete: (() -> Unit)? = null) {
        // 使用计数器跟踪异步操作完成状态
        var completedOperations = 0
        val totalOperations = 2 // XML获取 + 截图获取

        val checkCompletion = {
            completedOperations++
            Log.d(TAG, "异步操作完成: $completedOperations/$totalOperations")
            if (completedOperations >= totalOperations) {
                Log.d(TAG, "所有屏幕数据保存完成，执行回调")
                onComplete?.invoke()
            }
        }

        // 异步获取XML
        saveCurrScreenXML(checkCompletion)
        // 异步获取截图
        saveCurrentScreenShot(checkCompletion)
    }

    /**
     * 保存当前屏幕XML
     * 通过ActivityTracker获取当前Activity，使用ElementController获取元素树并转换为XML字符串
     * @param onComplete XML获取完成后的回调
     */
    private fun saveCurrScreenXML(onComplete: (() -> Unit)? = null) {
        nodeMap = kotlin.collections.HashMap()
        Log.d(TAG, "Node Renewed!!!!!!!")
        
        // 在更新当前XML之前，先保存上一次的XML
        if (currentScreenXML.isNotEmpty()) {
            previousScreenXML = currentScreenXML
            Log.d(TAG, "已保存上一次的XML，长度: ${previousScreenXML.length}")
        }
        
        // 获取当前Activity
        val currentActivity = ActivityTracker.getCurrentActivity()
        if (currentActivity == null) {
            Log.w(TAG, "当前Activity为空，无法获取元素树")
            currentScreenXML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<elementTree type=\"error\">\n  <element id=\"error\" type=\"Error\" text=\"当前Activity为空\" clickable=\"false\" focusable=\"false\" enabled=\"false\" visible=\"false\" bounds=\"\"/>\n</elementTree>"
            // 即使出错也要调用回调
            onComplete?.invoke()
            return
        }
        
        // 使用ElementController获取当前元素树
        ElementController.getCurrentElementTree(currentActivity) { genericElement ->
            // 构建nodeMap，将GenericElement树转换为index->GenericElement的HashMap
            buildNodeMap(genericElement)
            // 将GenericElement转换为XML字符串
            currentScreenXML = convertGenericElementToXmlString(genericElement)
            Log.d(TAG, "元素树XML生成完成，当前XML长度: ${currentScreenXML.length}")
            // XML生成完成后调用回调
            onComplete?.invoke()
        }
    }

    /**
     * 生成简化的XML
     * @param activity 当前Activity
     * @return XML字符串
     */
    private fun generateSimpleXML(activity: Activity): String {
        val activityName = activity.javaClass.simpleName
        val packageName = activity.packageName

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<hierarchy>
  <node resource-id="root" class="android.widget.FrameLayout" text="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
    <node resource-id="android:id/content" class="android.widget.FrameLayout" text="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
      <node resource-id="activity_info" class="android.widget.LinearLayout" text="Activity: $activityName" clickable="false" enabled="true" bounds="[0,0][1080,200]">
        <node resource-id="package_info" class="android.widget.TextView" text="Package: $packageName" clickable="false" enabled="true" bounds="[10,10][1070,50]"/>
        <node resource-id="activity_name" class="android.widget.TextView" text="Activity: $activityName" clickable="false" enabled="true" bounds="[10,60][1070,100]"/>
        <node resource-id="timestamp" class="android.widget.TextView" text="Time: ${System.currentTimeMillis()}" clickable="false" enabled="true" bounds="[10,110][1070,150]"/>
      </node>
      <node resource-id="test_button" class="android.widget.Button" text="Test Button" clickable="true" enabled="true" bounds="[100,300][980,400]"/>
      <node resource-id="test_edit" class="android.widget.EditText" text="" clickable="true" enabled="true" bounds="[100,450][980,550]"/>
    </node>
  </node>
</hierarchy>"""
    }





    /**
     * 将GenericElement转换为XML字符串
     * @param element 要转换的GenericElement
     * @return XML字符串
     */
    private fun convertGenericElementToXmlString(element: GenericElement): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<hierarchy>
${element.children.joinToString("") { it.toXmlString(1) }}
</hierarchy>"""
    }

    /**
     * XML转义字符处理
     */
    private fun String.escapeXml(): String {
        return this.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 保存当前屏幕截图
     * 支持Android API 24及以上版本
     * 注意：此方法仅进行内存截图，不需要存储权限
     * @param onComplete 截图完成后的回调
     */
    fun saveCurrentScreenShot(onComplete: (() -> Unit)? = null) {
        try {
            // 获取当前Activity
            val activity = ActivityTracker.getCurrentActivity()
            if (activity == null) {
                Log.e("MobileService", "无法获取当前Activity")
                // 即使出错也要调用回调
                onComplete?.invoke()
                return
            }

            // 确保在主线程执行UI操作
            if (Looper.myLooper() == Looper.getMainLooper()) {
                performScreenshot(activity, onComplete)
            } else {
                Handler(Looper.getMainLooper()).post {
                    performScreenshot(activity, onComplete)
                }
            }
        } catch (e: Exception) {
            Log.e("MobileService", "saveCurrentScreenShot异常", e)
            // 发生异常也要调用回调
            onComplete?.invoke()
        }
    }

    /**
     * 执行截图操作的具体实现
     * @param activity 当前Activity实例
     * @param onComplete 截图完成后的回调
     */
    private fun performScreenshot(activity: Activity, onComplete: (() -> Unit)? = null) {
        try {
            val rootView = activity.window?.decorView?.rootView
            if (rootView == null) {
                Log.e("MobileService", "无法获取根视图")
                onComplete?.invoke()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 使用PixelCopy
                val bitmap = Bitmap.createBitmap(
                    rootView.width, 
                    rootView.height, 
                    Bitmap.Config.ARGB_8888
                )
                
                PixelCopy.request(
                    activity.window,
                    Rect(0, 0, rootView.width, rootView.height),
                    bitmap,
                    { result ->
                        when (result) {
                            PixelCopy.SUCCESS -> {
                                Log.d("MobileService", "截图成功，尺寸: ${bitmap.width}x${bitmap.height}")
                                // 这里可以处理bitmap，比如保存到内存或显示
                                handleScreenshotResult(bitmap)
                            }
                            PixelCopy.ERROR_SOURCE_NO_DATA -> {
                                Log.e("MobileService", "截图失败: 源数据无效")
                                handleScreenshotResult(null)
                            }
                            PixelCopy.ERROR_SOURCE_INVALID -> {
                                Log.e("MobileService", "截图失败: 源无效")
                                handleScreenshotResult(null)
                            }
                            PixelCopy.ERROR_DESTINATION_INVALID -> {
                                Log.e("MobileService", "截图失败: 目标无效")
                                handleScreenshotResult(null)
                            }
                            else -> {
                                Log.e("MobileService", "截图失败: 未知错误 $result")
                                handleScreenshotResult(null)
                            }
                        }
                        // 无论成功失败都要调用回调
                        onComplete?.invoke()
                    },
                    Handler(Looper.getMainLooper())
                )
            } else {
                // Android 7.x 使用DrawingCache (已弃用但仍可用)
                try {
                    rootView.isDrawingCacheEnabled = true
                    rootView.buildDrawingCache(true)
                    val bitmap = rootView.drawingCache?.copy(Bitmap.Config.ARGB_8888, false)
                    
                    if (bitmap != null && !bitmap.isRecycled) {
                        Log.d("MobileService", "截图成功 (DrawingCache)，尺寸: ${bitmap.width}x${bitmap.height}")
                        handleScreenshotResult(bitmap)
                    } else {
                        Log.e("MobileService", "截图失败: bitmap为null或已回收")
                        handleScreenshotResult(null)
                    }
                } finally {
                    // 确保清理DrawingCache
                    rootView.isDrawingCacheEnabled = false
                    // DrawingCache是同步的，立即调用回调
                    onComplete?.invoke()
                }
            }
        } catch (e: SecurityException) {
            Log.e("MobileService", "截图失败: 安全异常", e)
            handleScreenshotResult(null)
            onComplete?.invoke()
        } catch (e: IllegalArgumentException) {
            Log.e("MobileService", "截图失败: 参数异常", e)
            handleScreenshotResult(null)
            onComplete?.invoke()
        } catch (e: Exception) {
            Log.e("MobileService", "截图过程中发生异常", e)
            handleScreenshotResult(null)
            onComplete?.invoke()
        }
    }

    /**
     * 安全地回收旧的截图，防止内存泄漏
     */
    private fun recycleOldScreenshot() {
        try {
            val oldScreenshot = currentScreenShot
            if (oldScreenshot != null && !oldScreenshot.isRecycled) {
                oldScreenshot.recycle()
                Log.d("MobileService", "已回收旧截图")
            }
        } catch (e: Exception) {
            Log.e("MobileService", "回收旧截图时发生异常", e)
        }
    }

    /**
     * 处理截图结果
     * @param bitmap 截图位图
     */
    private fun handleScreenshotResult(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                // 先回收旧的截图，防止内存泄漏
                recycleOldScreenshot()
                
                // 将新截图结果保存到currentScreenShot变量
                currentScreenShot = bitmap
                Log.d("MobileService", "截图处理完成，已保存到currentScreenShot")
        } else {
                Log.w("MobileService", "截图结果无效")
                // 回收旧截图并设置为null
                recycleOldScreenshot()
                currentScreenShot = null
            }
        } catch (e: Exception) {
            Log.e("MobileService", "处理截图结果时发生异常", e)
            // 发生异常时也要回收旧截图
            recycleOldScreenshot()
            currentScreenShot = null
        }
    }

    /**
     * 比较当前XML和上一个XML内容是否相同
     * 先删除resource-id属性，然后比较内容是否有变化
     * @return true如果XML内容相同，false如果不同
     */
    private fun isXmlContentSame(): Boolean {
        // 如果是第一次屏幕或者没有上一个XML，认为不同
        if (firstScreen || previousScreenXML.isEmpty()) {
            Log.d(TAG, "第一次屏幕或没有上一个XML，认为XML不同")
            return false
        }
        
        // 删除resource-id属性后比较XML内容
        val currentXmlWithoutResourceId = removeResourceIdFromXml(currentScreenXML)
        val previousXmlWithoutResourceId = removeResourceIdFromXml(previousScreenXML)
        
        val isSame = currentXmlWithoutResourceId == previousXmlWithoutResourceId
        Log.d(TAG, "XML比较结果(删除resource-id后): $isSame, 当前XML长度: ${currentXmlWithoutResourceId.length}, 上一个XML长度: ${previousXmlWithoutResourceId.length}")
        return isSame
    }

    /**
     * 从XML字符串中删除resource-id属性
     * @param xmlString 原始XML字符串
     * @return 删除resource-id属性后的XML字符串
     */
    private fun removeResourceIdFromXml(xmlString: String): String {
        // 使用正则表达式删除resource-id属性
        // 匹配 resource-id="..." 或 resource-id='...' 的模式
        val resourceIdPattern = """\s*resource-id\s*=\s*["'][^"']*["']""".toRegex()
        return xmlString.replace(resourceIdPattern, "")
    }

    /**
     * 发送XML未变化的错误信息（带最新截图）
     */
    private fun sendXmlUnchangedError() {
        val errorMessage = "点击执行动作之后，View视图发生了变化，但是当前XML的元素没有发生变化。请结合当前XML确认当前动作和任务是否执行成功。"
        Log.d(TAG, "发送XML未变化错误: $errorMessage")
        
        // 先强制更新截图，然后发送错误信息
        saveCurrentScreenShot {
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_ERROR
                errType = MobileGPTMessage.ERROR_TYPE_ACTION
                errMessage = errorMessage
                curXml = currentScreenXML    // 包含当前的XML
                preXml = previousScreenXML   // 包含上一次的XML
                action = currentAction       // 包含当前执行的动作
                instruction = currentInstruction // 包含当前发送的指令
                // 添加最新更新的截图
                screenshot = currentScreenShot
            }
            
            mExecutorService.execute { 
                mClient?.sendMessage(message)
            }
            
            // 发送错误信息后，重置状态变量
            screenNeedUpdate = false
            xmlPending = false
            firstScreen = false
        }
    }

    /**
     * 发送屏幕信息
     * 增加空值检查，避免空指针异常
     */
    private fun sendScreen() {
        try {
            // 检查截图是否可用
            val screenshot = currentScreenShot
            if (screenshot != null && !screenshot.isRecycled) {
                mExecutorService.execute {
                    try {
                        Log.d("MobileService", "开始发送截图")
                        val message = MobileGPTMessage().createScreenshotMessage(screenshot)
                        mClient?.sendMessage(message)
                    } catch (e: Exception) {
                        Log.e("MobileService", "发送截图失败", e)
                    }
                }
            } else {
                Log.w("MobileService", "截图不可用，跳过发送截图")
            }

            // 发送XML数据
            mExecutorService.execute {
                try {
                    Log.d("MobileService", "开始发送XML")
                    val message = MobileGPTMessage().createXmlMessage(currentScreenXML)
                    mClient?.sendMessage(message)
                } catch (e: Exception) {
                    Log.e("MobileService", "发送XML失败", e)
                }
            }
            // 发送屏幕信息后，设置以下变量都为false，不响应页面变化，同时不进行屏幕发送
        screenNeedUpdate = false
            xmlPending = false
        firstScreen = false
        } catch (e: Exception) {
            Log.e("MobileService", "sendScreen方法执行异常", e)
        }
    }

    /**
     * 显示操作列表
     */
    fun showActions() {
        // 因操作弹窗导致不发送屏幕
        xmlPending = false
        val message = MobileGPTMessage().createGetActionsMessage()
        mExecutorService.execute { mClient?.sendMessage(message) }
    }

    /**
     * 设置操作失败回调（带最新截图）
     */
    private fun setActionFailedRunnable(reason: String, delay: Int) {
        actionFailedRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
        actionFailedRunnable = Runnable {
            Log.e(TAG, reason)
            // 先强制更新截图，然后发送错误信息
            saveCurrentScreenShot {
                val message = MobileGPTMessage().apply {
                    messageType = MobileGPTMessage.TYPE_ERROR
                    errType = MobileGPTMessage.ERROR_TYPE_ACTION
                    errMessage = reason
                    curXml = currentScreenXML    // 包含当前的XML
                    preXml = previousScreenXML   // 包含上一次的XML
                    action = currentAction       // 包含当前执行的动作
                    instruction = currentInstruction // 包含当前发送的指令
                    // 添加最新更新的截图
                    screenshot = currentScreenShot
                }
                mExecutorService.execute { mClient?.sendMessage(message) }
            }
        }
        actionFailedRunnable?.let {
            mainThreadHandler.postDelayed(it, delay.toLong())
        }
        // 以下失败后屏幕更新逻辑如何设置
//        xmlPending = true
//        screenNeedUpdate = true
//        firstScreen = true
    }

    /**
     * 重置服务状态
     */
    private fun reset() {
//        mClient?.disconnect()
//        mClient = null
        xmlPending = false
        screenNeedUpdate = false
        firstScreen = false
        currentScreenXML = ""
        previousScreenXML = ""  // 重置上一次的XML
        currentAction = ""      // 重置当前执行的动作
        currentInstruction = "" // 重置当前发送的指令
        // 清楚屏幕发送的任务
        screenUpdateWaitRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
        screenUpdateTimeoutRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
    }

    /**
     * 服务关闭
     * 清理所有资源，防止内存泄漏
     */
    override fun onDestroy() {
        try {

            // 清理页面变化监听
            removeViewTreeObserver()
            ActivityTracker.setActivityChangeListener(null)

            // 清理防抖任务
            pageChangeDebounceRunnable?.let {
                mainThreadHandler.removeCallbacks(it)
            }
            
            // 清理截图资源
            recycleOldScreenshot()
            currentScreenShot = null
            
            // 清理其他资源
            unregisterReceiver(stringReceiver)
            mClient?.disconnect()
            
            // 清理悬浮窗资源
            if (::agentFloatingWindow.isInitialized) {
                agentFloatingWindow.cleanup()
            }
            
            // 关闭线程池
            if (::mExecutorService.isInitialized) {
                mExecutorService.shutdown()
            }
            
            Log.d(TAG, "MobileService已销毁，所有资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "销毁服务时发生异常", e)
        } finally {
        super.onDestroy()
        }
    }

    /**
     * 构建nodeMap，将GenericElement树转换为index->GenericElement的HashMap
     */
    private fun buildNodeMap(element: GenericElement) {
        fun traverseElement(elem: GenericElement) {
            nodeMap?.put(elem.index, elem)
            elem.children.forEach { child ->
                traverseElement(child)
            }
        }
        traverseElement(element)
    }

    /**
     * 初始化网络连接
     */
    private fun initNetworkConnection() {
        try {
            Log.d(TAG, "尝试连接服务器: ${MobileGPTGlobal.HOST_IP}:${MobileGPTGlobal.HOST_PORT}")
            mClient = MobileGPTClient(MobileGPTGlobal.HOST_IP, MobileGPTGlobal.HOST_PORT)
            mClient!!.connect()
            mClient!!.receiveMessages(object : MobileGPTClient.OnMessageReceived {
                override fun onReceived(message: String) {
                    // 确保消息处理在主线程中执行，避免UI操作线程问题
                    Handler(Looper.getMainLooper()).post {
                        if (message != null) {
                            handleResponse(message)
                        }
                    }
                }
            })
            Log.d(TAG, "成功连接到服务器")
        } catch (e: IOException) {
            Log.e(TAG, "服务器连接失败: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "网络连接初始化过程中发生未知错误: ${e.message}", e)
        }
    }
}