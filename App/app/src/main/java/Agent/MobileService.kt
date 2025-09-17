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
import android.view.ViewTreeObserver
import android.view.WindowManager
import controller.ElementController
import controller.GenericElement
import controller.NativeController
import controller.PageSniffer
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
        private const val TAG = "MobileGPT_Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MobileGPTServiceChannel"
    }

    private val binder = LocalBinder()
    private lateinit var wm: WindowManager
    private var mClient: MobileGPTClient? = null
    private lateinit var mSpeech: MobileGPTSpeechRecognizer
//    lateinit var mAskPopUp: AskPopUp
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

    /**
     * 本地绑定器类
     */
    inner class LocalBinder : Binder() {
        fun getService(): MobileService = this@MobileService
    }

    /**
     * 广播接收器，用于接收指令
     */
    private val stringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MobileGPTGlobal.STRING_ACTION) {

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
                .setSmallIcon(R.drawable.ic_menu_info_details) // 使用自定义图标
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MobileGPT Service")
                .setContentText("MobileGPT service is running")
                .setSmallIcon(R.drawable.ic_menu_info_details)
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
                // 使用回调确保saveCurrScreen完成后再调用sendScreen
                saveCurrScreen {
                    sendScreen()
                }
            }
        }

        screenUpdateTimeoutRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "screen update timeout")
                mainThreadHandler.removeCallbacks(screenUpdateWaitRunnable!!)
                // 使用回调确保saveCurrScreen完成后再调用sendScreen
                saveCurrScreen {
                    sendScreen()
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
                // for First screen, we wait 5 s for loading app
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
            Log.d(TAG, "xmlPending为false 不执行屏幕更新")
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
                     onPageChanged("视图树布局变化")
                 } catch (e: Exception) {
                     Log.e(TAG, "处理视图树变化时发生异常", e)
                 }
             }

            // 添加监听器
            viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

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
        } catch (e: Exception) {
            Log.e(TAG, "移除ViewTreeObserver监听时发生异常", e)
        } finally {
            currentViewTreeObserver = null
            globalLayoutListener = null
            currentMonitoredActivity = null
        }
    }

    /**
     * 页面变化处理方法
     * 当检测到页面变化时调用WaitScreenUpdte方法
     * @param reason 变化原因
     */
    private fun onPageChanged(reason: String) {
        val currentTime = System.currentTimeMillis()
        Log.d(TAG, "处理页面变化: $reason")
        WaitScreenUpdate()
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
                    // mSpeech.speak(content, false)
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
                preXml = previousScreenXML  // 包含上一次的XML
                action = currentAction      // 包含当前执行的动作
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
//        mAskPopUp.setQuestion(info, question)
//        mSpeech.speak(question, true)
//        mAskPopUp.showPopUp()
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
     */
    private fun executeClickAction(activity: Activity, element: GenericElement) {
        // 首先检查目标元素是否可点击
        if (element.clickable && element.enabled) {
            // 目标元素可点击，直接执行
            ElementController.clickElement(activity, element.resourceId) { success ->
                if (success) {
                    Log.d(TAG, "点击动作执行成功")
                    screenNeedUpdate = true
                    xmlPending = true
                } else {
                    Log.e(TAG, "点击动作执行失败")
                    sendActionError("点击动作执行失败")
                }
            }
        } else {
            // 目标元素不可点击，尝试查找最近的可点击节点
            Log.d(TAG, "目标元素不可点击，尝试查找最近的可点击节点")
            findNearestClickableNode(activity, element) { clickableElement ->
                if (clickableElement != null) {
                    Log.d(TAG, "找到最近的可点击节点: ${clickableElement.resourceId}")
                    
                    // 检查是否是可滚动的容器（如ListView、RecyclerView等）
                    if (isScrollableContainer(clickableElement)) {
                        // 对于可滚动容器，使用目标元素的坐标进行点击
                        Log.d(TAG, "检测到可滚动容器，使用目标元素坐标点击")
                        clickByCoordinate(activity, element, clickableElement) { success ->
                            if (success) {
                                Log.d(TAG, "使用坐标点击成功")
                                screenNeedUpdate = true
                                xmlPending = true
                            } else {
                                Log.e(TAG, "使用坐标点击失败")
                                val remark = buildClickableNodeRemark(element, clickableElement, false)
                                sendActionError("点击动作执行失败", remark)
                            }
                        }
                    } else {
                        // 普通元素，直接点击
                        ElementController.clickElement(activity, clickableElement.resourceId) { success ->
                            if (success) {
                                Log.d(TAG, "使用最近可点击节点执行点击成功")
                                screenNeedUpdate = true
                                xmlPending = true
                            } else {
                                Log.e(TAG, "使用最近可点击节点执行点击失败")
                                val remark = buildClickableNodeRemark(element, clickableElement, false)
                                sendActionError("点击动作执行失败", remark)
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "未找到可点击的节点")
                    val remark = buildNoClickableNodeRemark(element)
                    sendActionError("点击动作执行失败", remark)
                }
            }
        }
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
    private fun executeLongClickAction(activity: Activity, element: GenericElement) {
        ElementController.longClickElement(activity, element.resourceId) { success ->
            if (success) {
                Log.d(TAG, "长按动作执行成功")
                screenNeedUpdate = true
                xmlPending = true
            } else {
                Log.e(TAG, "长按动作执行失败")
                sendActionError("长按动作执行失败")
            }
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
    private fun executeGoHomeAction(activity: Activity) {
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
     * 发送动作错误信息
     */
    private fun sendActionError(errorMessage: String, remark: String = "") {
        val message = MobileGPTMessage().apply {
            messageType = MobileGPTMessage.TYPE_ERROR
            errType = MobileGPTMessage.ERROR_TYPE_ACTION
            errMessage = errorMessage
            preXml = previousScreenXML
            action = currentAction
            instruction = currentInstruction
            this.remark = remark
        }
        mExecutorService.execute { mClient?.sendMessage(message) }
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
    private fun clickByCoordinate(activity: Activity, targetElement: GenericElement, containerElement: GenericElement, callback: (Boolean) -> Unit) {
        // 计算目标元素的中心坐标
        val centerX = (targetElement.bounds.left + targetElement.bounds.right) / 2f
        val centerY = (targetElement.bounds.top + targetElement.bounds.bottom) / 2f
        
        Log.d(TAG, "使用坐标点击: ($centerX, $centerY)")
        
        // 使用NativeController的坐标点击功能
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.clickByCoordinate(activity, centerX, centerY) { success ->
                    callback(success)
                }
            }
            else -> {
                // 对于其他类型，尝试使用ElementController
                ElementController.clickElement(activity, containerElement.resourceId) { success ->
                    callback(success)
                }
            }
        }
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
     * 设置操作失败回调
     */
    private fun setActionFailedRunnable(reason: String, delay: Int) {
        actionFailedRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
        actionFailedRunnable = Runnable {
            Log.e(TAG, reason)
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_ERROR
                errType = MobileGPTMessage.ERROR_TYPE_ACTION
                errMessage = reason
                preXml = previousScreenXML  // 包含上一次的XML
                action = currentAction      // 包含当前执行的动作
                instruction = currentInstruction // 包含当前发送的指令
            }
            mExecutorService.execute { mClient?.sendMessage(message) }
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