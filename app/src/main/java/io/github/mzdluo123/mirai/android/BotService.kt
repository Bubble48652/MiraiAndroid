@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.mzdluo123.mirai.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mzdluo123.mirai.android.script.ScriptManager
import io.github.mzdluo123.mirai.android.utils.MiraiAndroidStatus
import io.github.mzdluo123.mirai.android.utils.register
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.command.CommandOwner
import net.mamoe.mirai.console.command.ConsoleCommandSender
import net.mamoe.mirai.console.command.ConsoleCommandSender.sendMessage
import net.mamoe.mirai.console.command.ContactCommandSender
import net.mamoe.mirai.console.utils.checkManager
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.utils.SimpleLogger
import kotlin.system.exitProcess

class BotService : Service(), CommandOwner {
    lateinit var androidMiraiConsole: AndroidMiraiConsole
        private set

    private val binder by lazy { BotBinder() }
    private var isStart = false

    companion object {
        const val START_SERVICE = 0
        const val STOP_SERVICE = 1
        const val NOTIFICATION_ID = 1
        const val OFFLINE_NOTIFICATION_ID = 3
        const val TAG = "BOT_SERVICE"
    }

    private fun createNotification() {
        //使用兼容版本
        NotificationCompat.Builder(this, BotApplication.SERVICE_NOTIFICATION)
            .setSmallIcon(R.drawable.ic_extension_black_24dp)//设置状态栏的通知图标
            .setAutoCancel(false) //禁止用户点击删除按钮删除
            .setOngoing(true) //禁止滑动删除
            .setShowWhen(true) //右上角的时间显示
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentTitle("MiraiAndroid未登录") //创建通知
            .setContentText("请完成登录并将软件添加到系统后台运行白名单确保能及时处理消息")
            .build()
            .let {
                startForeground(NOTIFICATION_ID, it) //设置为前台服务
            }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            intent?.getIntExtra("action", START_SERVICE).let { action ->
                when (action) {
                    START_SERVICE -> startConsole(intent)
                    STOP_SERVICE -> stopConsole()
                }
            }
        }catch (e:Exception){
            androidMiraiConsole.pushLog(0L, "发生错误 $e")
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        androidMiraiConsole = AndroidMiraiConsole(baseContext)
    }

    private fun autoLogin(intent: Intent) {
        val qq = intent.getLongExtra("qq", 0)
        val pwd = intent.getStringExtra("pwd")
        if (qq == 0L) return

        //CommandManager.runCommand(ConsoleCommandSender, "login $qq $pwd")
        androidMiraiConsole.pushLog(0L, "[INFO] 自动登录....")
        val handler = CoroutineExceptionHandler { _, throwable ->
            androidMiraiConsole.pushLog(0L, "[ERROR] 自动登录失败 $throwable")
        }

        val bot = Bot(qq, pwd!!.chunkedHexToBytes()) {
            fileBasedDeviceInfo(getExternalFilesDir(null)!!.absolutePath + "/device.json")
            this.loginSolver = MiraiConsole.frontEnd.createLoginSolver()
            this.botLoggerSupplier = {
                SimpleLogger("[BOT $qq]") { _, message, e ->
                    androidMiraiConsole.pushLog(0L, "[INFO] $message")
                    e?.also {
                        androidMiraiConsole.pushLog(0L, "[BOT ERROR $qq] $it")
                    }?.printStackTrace()
                }
            }
            this.networkLoggerSupplier = {
                SimpleLogger("BOT $qq") { _, message, e ->
                    androidMiraiConsole.pushLog(0L, "[NETWORK] $message")
                    e?.also {
                        androidMiraiConsole.pushLog(0L, "[NETWORK ERROR] $it")
                    }?.printStackTrace()
                }
            }
        }
        GlobalScope.launch(handler) { bot.login() }
        bot.subscribeMessages {
            startsWith("/") { message ->
                if (bot.checkManager(this.sender.id))
                    CommandManager.runCommand(ContactCommandSender(this.subject), message)
            }
        }

        GlobalScope.launch(handler) { sendMessage("$qq login successes") }
        MiraiConsole.frontEnd.pushBot(bot)
    }

    private fun registerDefaultCommand() {
        register(_description = "显示MiraiAndroid运行状态", _name = "android") { sender, args ->
            sender.sendMessage(MiraiAndroidStatus.recentStatus().format())
            true
        }
        register(_description = "查看已加载的脚本", _name = "script", _usage = "script") { sender, args ->
            sender.sendMessage(buildString {
                append("已加载 ${ScriptManager.instance.hosts.size}个脚本\n")
                ScriptManager.instance.hosts.joinTo(this, "\n") { it.scriptFile.name }
            })
            true
        }
    }

    private fun startConsole(intent: Intent?) {
        if (isStart) return
        isStart = true
        MiraiAndroidStatus.startTime = System.currentTimeMillis()
        MiraiConsole.start(
            androidMiraiConsole,
            path = getExternalFilesDir(null).toString()
        )
        createNotification()
        registerDefaultCommand()
        intent?.let { autoLogin(it) }
    }

    private fun stopConsole() {
        ScriptManager.instance.disableAll()
        MiraiConsole.stop()
        stopForeground(true)
        stopSelf()
        exitProcess(0)
    }

    inner class BotBinder : IbotAidlInterface.Stub() {
        override fun runCmd(cmd: String?) {
            cmd?.let {
                CommandManager.runCommand(ConsoleCommandSender, it)
            }
        }

        override fun getLog(): Array<String> {
            //防止
            // ClassCastException: java.lang.Object[] cannot be cast to java.lang.String[]
            // 不知道有没有更好的写法

            return androidMiraiConsole.logStorage.toArray(
                arrayOfNulls<String>(
                    androidMiraiConsole.logStorage.size
                )
            )
        }

        override fun submitVerificationResult(result: String?) {
            result?.let {
                androidMiraiConsole.loginSolver.verificationResult.complete(it)
            }
        }

        override fun clearLog() {
            androidMiraiConsole.logStorage.clear()
        }

        override fun getUrl(): String = androidMiraiConsole.loginSolver.url

        override fun getCaptcha(): ByteArray = androidMiraiConsole.loginSolver.captchaData

        override fun sendLog(log: String?) {
            androidMiraiConsole.logStorage.add(log)
        }
    }

    private fun String.chunkedHexToBytes(): ByteArray = this.asSequence().chunked(2).map { (it[0].toString() + it[1]).toUByte(16).toByte() }
            .toList().toByteArray()

    /* 多进程调试辅助
    init {
        Debug.waitForDebugger()
    }
    */

}
