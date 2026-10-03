package com.ruyolidus.multiplayerbridge

import android.app.Application
import com.ruyolidus.multiplayerbridge.data.AndroidApkInspector
import com.ruyolidus.multiplayerbridge.data.GameVault
import java.io.File

class BridgeApplication : Application() {
    val gameVault by lazy { GameVault(File(filesDir, "games"), AndroidApkInspector(packageManager)) }
}
