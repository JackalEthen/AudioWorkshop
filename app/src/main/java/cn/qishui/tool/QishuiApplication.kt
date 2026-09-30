package cn.qishui.tool

import android.app.Application
import cn.qishui.tool.app.AppContainer
import cn.qishui.tool.app.ProcessIdentity
import cn.qishui.tool.util.SourceLog

class QishuiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!ProcessIdentity.isMainProcess(this)) return
        SourceLog.init(this)
        container = AppContainer(this)
        container.encoderClient.bind()
    }

    override fun onTerminate() {
        if (ProcessIdentity.isMainProcess(this)) {
            container.encoderClient.unbind()
        }
        super.onTerminate()
    }
}
