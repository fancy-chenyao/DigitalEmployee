package Agent

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
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var actionFailedRunnable: Runnable? = null
    private var screenUpdateTimeoutRunnable: Runnable? = null
    private var screenUpdateWaitRunnable: Runnable? = null
    private var clickRetryRunnable: Runnable? = null
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
    inner class LocalBinder : Binder()

    /**
     * 广播接收器，用于接收指令
     */
    private val stringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MobileGPTGlobal.STRING_ACTION) {
                reset()
                val receivedInstruction = intent.getStringExtra(MobileGPTGlobal.INSTRUCTION_EXTRA)
                if (receivedInstruction != null) {
                    instruction = receivedInstruction
                    Log.d(TAG, "receive broadcast")
                    mExecutorService.execute { 
                        initNetworkConnection()
                        // 记录当前发送的指令
                        currentInstruction = receivedInstruction
                        Log.d(TAG, "记录当前发送的指令: $currentInstruction")
                        val message = MobileGPTMessage().createInstructionMessage(receivedInstruction)
                        mClient?.sendMessage(message)
                        // 发送指令后启动屏幕更新
                        mainThreadHandler.post {
                            startPeriodicScreenUpdate()
                        }
                    }
                } else {
                    Log.e(TAG, "Received null instruction from intent")
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
                .setSmallIcon(android.R.drawable.ic_menu_info_details) // 使用自定义图标
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stringReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stringReceiver, intentFilter)
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mSpeech = MobileGPTSpeechRecognizer(this)
        mMobileGPTGlobal = MobileGPTGlobal.getInstance()

        // 初始化Runnable
        screenUpdateWaitRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "screen update waited")
                mainThreadHandler.removeCallbacks(screenUpdateTimeoutRunnable!!)
                // 使用回调确保saveCurrScreen完成后再调用sendScreen
                saveCurrScreen()
                sendScreen()
            }
        }

        screenUpdateTimeoutRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "screen update timeout")
                mainThreadHandler.removeCallbacks(screenUpdateWaitRunnable!!)
                // 使用回调确保saveCurrScreen完成后再调用sendScreen
                saveCurrScreen()
                sendScreen()
            }
        }

        // 初始化页面变化监听
        initPageChangeListener()

        // 延迟初始化网络连接，不阻塞服务启动
        mExecutorService.execute {
            initNetworkConnection()
            // 在网络连接初始化后再初始化 mAskPopUp
//            mainThreadHandler.post {
//                mAskPopUp = AskPopUp(this, mClient!!, mSpeech)
//            }
        }
        
        Log.d(TAG, "MobileService 初始化完成")
        
        // // 延迟启动定时屏幕更新，等待Activity准备就绪
        // mainThreadHandler.postDelayed({
        //     startPeriodicScreenUpdate()
        // }, 2000) // 延迟2秒启动
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
            mExecutorService.execute { launchAppAndInit(selectedApp) }
            return
        } else if (message.startsWith("$$##$$")) {
            val subtask = message.substring(6)
            return
        } else if (message.startsWith("$$$$$")) {
            // 断开服务器连接
            Log.d(TAG, "-----------Task finished--------")
            mSpeech.speak("任务已完成。", false)
            mClient?.disconnect()
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
    private fun sendActionError(errorMessage: String) {
        val message = MobileGPTMessage().apply {
            messageType = MobileGPTMessage.TYPE_ERROR
            errType = MobileGPTMessage.ERROR_TYPE_ACTION
            errMessage = errorMessage
            preXml = previousScreenXML
            action = currentAction
            instruction = currentInstruction
        }
        mExecutorService.execute { mClient?.sendMessage(message) }
    }


    /**
     * 保存当前屏幕信息
     */
    private fun saveCurrScreen() {
        screenNeedUpdate = false
        xmlPending = false
        firstScreen = false
        saveCurrScreenXML()
        saveCurrentScreenShot()
    }

    /**
     * 保存当前屏幕XML
     * 通过ActivityTracker获取当前Activity，使用ElementController获取元素树并转换为XML字符串
     */
    private fun saveCurrScreenXML() {
        nodeMap = HashMap()
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
            return
        }
        
        // 使用ElementController获取当前元素树
        ElementController.getCurrentElementTree(currentActivity) { genericElement ->
            // 构建nodeMap，将GenericElement树转换为index->GenericElement的HashMap
            buildNodeMap(genericElement)
            // 将GenericElement转换为XML字符串
            currentScreenXML = convertGenericElementToXmlString(genericElement)
            Log.d(TAG, "元素树XML生成完成，当前XML长度: ${currentScreenXML.length}")
        }
    }

    /**
     * 构建nodeMap，将GenericElement树转换为index->GenericElement的HashMap
     * @param element 根元素
     */
    private fun buildNodeMap(element: GenericElement) {
        // 递归遍历所有元素，将index作为key，GenericElement作为value存入nodeMap
        fun traverseElement(elem: GenericElement) {
            nodeMap?.put(elem.index, elem)
            elem.children.forEach { child ->
                traverseElement(child)
            }
        }
        traverseElement(element)
        Log.d(TAG, "NodeMap构建完成，包含 ${nodeMap?.size} 个元素")
    }

    /**
     * 生成简化的XML
     * @param activity 当前Activity
     * @return XML字符串
     */
    private fun generateSimpleXML(activity: android.app.Activity): String {
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
//        return """<?xml version="1.0" encoding="UTF-8"?>
//<elementTree type="${element.type.escapeXml()}">
//${element.children.joinToString("") { it.toXmlString(1) }}
//</elementTree>"""
//    }

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
     */
    fun saveCurrentScreenShot() {
        try {
            // 获取当前Activity
            val activity = ActivityTracker.getCurrentActivity()
            if (activity == null) {
                Log.e("MobileService", "无法获取当前Activity")
                return
            }

            // 确保在主线程执行UI操作
            if (Looper.myLooper() == Looper.getMainLooper()) {
                performScreenshot(activity)
            } else {
                Handler(Looper.getMainLooper()).post {
                    performScreenshot(activity)
                }
            }
        } catch (e: Exception) {
            Log.e("MobileService", "saveCurrentScreenShot异常", e)
        }
    }

    /**
     * 执行截图操作的具体实现
     * @param activity 当前Activity实例
     */
    private fun performScreenshot(activity: Activity) {
        try {
            val rootView = activity.window?.decorView?.rootView
            if (rootView == null) {
                Log.e("MobileService", "无法获取根视图")
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
                            }
                            PixelCopy.ERROR_SOURCE_INVALID -> {
                                Log.e("MobileService", "截图失败: 源无效")
                            }
                            PixelCopy.ERROR_DESTINATION_INVALID -> {
                                Log.e("MobileService", "截图失败: 目标无效")
                            }
                            else -> {
                                Log.e("MobileService", "截图失败: 未知错误 $result")
                            }
                        }
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
                    }
                } finally {
                    // 确保清理DrawingCache
                    rootView.isDrawingCacheEnabled = false
                }
            }
        } catch (e: SecurityException) {
            Log.e("MobileService", "截图失败: 安全异常", e)
        } catch (e: IllegalArgumentException) {
            Log.e("MobileService", "截图失败: 参数异常", e)
        } catch (e: Exception) {
            Log.e("MobileService", "截图过程中发生异常", e)
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
        mainThreadHandler.removeCallbacks(actionFailedRunnable!!)
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
        mainThreadHandler.postDelayed(actionFailedRunnable!!, delay.toLong())
    }
    /**
     * 启动应用并初始化
     */
    fun launchAppAndInit(packageName: String) {
        Log.d(TAG, "package name: $packageName")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Log.d(TAG, "intent null")
        }
        xmlPending = true
        screenNeedUpdate = true
        firstScreen = true
    }

    /**
     * 重置服务状态
     */
    private fun reset() {
        mClient?.disconnect()
        mClient = null
        xmlPending = false
        screenNeedUpdate = false
        firstScreen = false
        currentScreenXML = ""
        previousScreenXML = ""  // 重置上一次的XML
        currentAction = ""      // 重置当前执行的动作
        currentInstruction = "" // 重置当前发送的指令
        mMobileGPTGlobal = MobileGPTGlobal.reset()

                
        // 停止屏幕更新
        stopPeriodicScreenUpdate()
        
        // 清理页面变化监听
        removeViewTreeObserver()
        ActivityTracker.setActivityChangeListener(null)

        // 清理防抖任务
        pageChangeDebounceRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }

//        mainThreadHandler.post {
//            mSpeech = MobileGPTSpeechRecognizer(this@MobileService)
//            mAskPopUp = AskPopUp(this@MobileService, mClient!!, mSpeech)
//        }
    }



    /**
     * 启动定时屏幕更新功能
     * 增强Activity状态检查
     */
    private fun startPeriodicScreenUpdate() {
        try {
            isScreenUpdateEnabled = true
            screenUpdateRunnable = object : Runnable {
                override fun run() {
                    if (isScreenUpdateEnabled) {
                        try {
                            // 检查是否有可用的Activity
                            val currentActivity = ActivityTracker.getCurrentActivity()
                            if (currentActivity == null) {
                                Log.d(TAG, "当前无Activity，跳过屏幕更新")
                                // 继续下次循环
                                mainThreadHandler.postDelayed(this, 3000)
                                return
                            }
                            
                            // 在后台线程执行屏幕保存和发送
                            mExecutorService.execute {
                                try {
                                    saveCurrScreen()
                                    sendScreen()
                                    Log.d("MobileService", currentScreenXML)
                                } catch (e: Exception) {
                                    Log.e(TAG, "屏幕更新过程中发生异常", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "定时任务执行异常", e)
                        }
                        // 1秒后再次执行
                        mainThreadHandler.postDelayed(this, 3000)
                    }
                }
            }
            // 启动定时任务
            mainThreadHandler.post(screenUpdateRunnable!!)
            Log.d(TAG, "定时屏幕更新已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动定时屏幕更新失败", e)
        }
    }
    
    /**
     * 停止定时屏幕更新功能
     */
    private fun stopPeriodicScreenUpdate() {
        isScreenUpdateEnabled = false
        screenUpdateRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
        Log.d(TAG, "定时屏幕更新已停止")
    }

    /**
     * 服务关闭
     * 清理所有资源，防止内存泄漏
     */
    override fun onDestroy() {
        try {
            // 停止屏幕更新
            stopPeriodicScreenUpdate()
            
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
     * 初始化网络连接
     */
    private fun initNetworkConnection() {
        try {
            Log.d(TAG, "尝试连接服务器: ${MobileGPTGlobal.HOST_IP}:${MobileGPTGlobal.HOST_PORT}")
            mClient = MobileGPTClient(MobileGPTGlobal.HOST_IP, MobileGPTGlobal.HOST_PORT)
            mClient!!.connect()
            mClient!!.receiveMessages(object : MobileGPTClient.OnMessageReceived {
                override fun onReceived(message: String) {
                    Thread {
                        if (message != null) {
                            handleResponse(message)
                        }
                    }.start()
                }
            })
            Log.d(TAG, "成功连接到服务器")
        } catch (e: IOException) {
            Log.e(TAG, "服务器连接失败: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "网络连接初始化过程中发生未知错误: ${e.message}", e)
        }
    }

    /**
     * 初始化页面变化监听
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Activity变化时发生异常", e)
        }
    }

    /**
     * 设置ViewTreeObserver监听
     * @param activity 要监听的Activity
     */
    private fun setupViewTreeObserver(activity: Activity) {
        try {
            removeViewTreeObserver() // 先移除旧的监听

            val rootView = activity.window?.decorView?.rootView
            if (rootView == null) {
                Log.w(TAG, "无法获取根视图，跳过ViewTreeObserver设置")
                return
            }

            currentViewTreeObserver = rootView.viewTreeObserver
            currentMonitoredActivity = activity

            globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                try {
                    val currentTime = System.currentTimeMillis()
                    // 防抖处理：如果距离上次变化时间太短，则忽略
                    if (currentTime - lastPageChangeTime < PAGE_CHANGE_DEBOUNCE_DELAY) {
                        return@OnGlobalLayoutListener
                    }
                    lastPageChangeTime = currentTime

                    // 取消之前的防抖任务
                    pageChangeDebounceRunnable?.let {
                        mainThreadHandler.removeCallbacks(it)
                    }

                    // 设置新的防抖任务
                    pageChangeDebounceRunnable = Runnable {
                        onPageChanged("ViewTreeObserver检测到布局变化")
                    }
                    mainThreadHandler.postDelayed(pageChangeDebounceRunnable!!, PAGE_CHANGE_DEBOUNCE_DELAY)

                } catch (e: Exception) {
                    Log.e(TAG, "ViewTreeObserver回调中发生异常", e)
                }
            }

            currentViewTreeObserver?.addOnGlobalLayoutListener(globalLayoutListener)
            Log.d(TAG, "ViewTreeObserver监听已设置，Activity: ${activity.javaClass.simpleName}")

        } catch (e: Exception) {
            Log.e(TAG, "设置ViewTreeObserver时发生异常", e)
        }
    }

    /**
     * 移除ViewTreeObserver监听
     */
    private fun removeViewTreeObserver() {
        try {
            currentViewTreeObserver?.let { observer ->
                globalLayoutListener?.let { listener ->
                    if (observer.isAlive) {
                        observer.removeOnGlobalLayoutListener(listener)
                    }
                }
            }
            currentViewTreeObserver = null
            globalLayoutListener = null
            currentMonitoredActivity = null
            Log.d(TAG, "ViewTreeObserver监听已移除")
        } catch (e: Exception) {
            Log.e(TAG, "移除ViewTreeObserver时发生异常", e)
        }
    }

    /**
     * 处理页面变化
     * @param reason 变化原因
     */
    private fun onPageChanged(reason: String) {
        val currentTime = System.currentTimeMillis()
        Log.d(TAG, "处理页面变化: $reason")
        WaitScreenUpdate()
    }

    /**
     * 手动触发页面变化检测
     */
    fun triggerPageChangeDetection() {
        Log.d(TAG, "手动触发页面变化检测")
        onPageChanged("手动触发检测")
    }

    /**
     * 检查页面变化监听是否活跃
     * @return 是否活跃
     */
    fun isPageChangeListenerActive(): Boolean {
        return currentViewTreeObserver != null &&
               globalLayoutListener != null &&
               currentMonitoredActivity != null
    }

    /**
     * 等待屏幕更新
     */
    private fun WaitScreenUpdate() {
        // xmlPending主要为了控制该函数是否需要响应页面变化，例如在showActions时，避免因为弹出悬浮窗导致监听页面变化进而发送XML
        if (xmlPending) {
            if (firstScreen && screenNeedUpdate) {
                // for First screen, we wait 5 s for loading app
                Log.d(TAG, "第一次打开应用，设置延迟强制发送")
                screenUpdateTimeoutRunnable?.let {
                    mainThreadHandler.postDelayed(it, 2000)
                }
                screenNeedUpdate = false

            } else if (!firstScreen) {
                if (screenNeedUpdate) {
                    Log.d(TAG, "设置防抖等待发送以及延迟强制发送")
                } else {
                    Log.d(TAG, "只设置防抖等待发送")
                }
                if (screenNeedUpdate) {
                    // 取消点击动作的回调
                    clickRetryRunnable?.let {
                        mainThreadHandler.removeCallbacks(it)
                    }
                    // 设置延迟强制发送
                    screenUpdateTimeoutRunnable?.let {
                        mainThreadHandler.postDelayed(it, 2000)
                    }
                    screenNeedUpdate = false
                }
            }
        }
    }
}