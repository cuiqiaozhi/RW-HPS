/*
 * Copyright 2020-2022 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package com.github.dr.rwserver

import com.github.dr.rwserver.command.ClientCommands
import com.github.dr.rwserver.command.CoreCommands
import com.github.dr.rwserver.command.LogCommands
import com.github.dr.rwserver.core.Initialization
import com.github.dr.rwserver.core.thread.Threads.newThreadCore
import com.github.dr.rwserver.custom.LoadCoreCustomPlugin
import com.github.dr.rwserver.data.base.BaseConfig
import com.github.dr.rwserver.data.global.Data
import com.github.dr.rwserver.data.global.NetStaticData
import com.github.dr.rwserver.data.plugin.PluginEventManage.Companion.add
import com.github.dr.rwserver.data.plugin.PluginManage
import com.github.dr.rwserver.data.plugin.PluginManage.init
import com.github.dr.rwserver.data.plugin.PluginManage.loadSize
import com.github.dr.rwserver.data.plugin.PluginManage.runInit
import com.github.dr.rwserver.data.plugin.PluginManage.runOnEnable
import com.github.dr.rwserver.data.plugin.PluginManage.runRegisterEvents
import com.github.dr.rwserver.func.StrCons
import com.github.dr.rwserver.game.Event
import com.github.dr.rwserver.game.EventGlobal
import com.github.dr.rwserver.game.EventGlobalType.ServerLoadEvent
import com.github.dr.rwserver.io.output.GameOutputStream
import com.github.dr.rwserver.net.netconnectprotocol.realize.GameVersionPacket
import com.github.dr.rwserver.util.encryption.Base64.decodeString
import com.github.dr.rwserver.util.file.FileUtil.Companion.getFolder
import com.github.dr.rwserver.util.file.FileUtil.Companion.readFileListString
import com.github.dr.rwserver.util.file.FileUtil.Companion.setFilePath
import com.github.dr.rwserver.util.game.CommandHandler
import com.github.dr.rwserver.util.game.Events
import com.github.dr.rwserver.util.log.Log
import com.github.dr.rwserver.util.log.Log.clog
import com.github.dr.rwserver.util.log.Log.error
import com.github.dr.rwserver.util.log.Log.info
import com.github.dr.rwserver.util.log.Log.set
import com.github.dr.rwserver.util.log.Log.setCopyPrint
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Welcome to Bugs RW-HPS !
 * Welcome to the RW-HPS Team ! Add a copy of your code to the project
 *
 * RW-HPS Team Welcome to You
 */

/**
 * @author Dr
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        //System.setProperty("file.encoding","UTF-8")
        Initialization()

        set("ALL")
        setCopyPrint(true)

        Logger.getLogger("io.netty").level = Level.OFF

        println(Data.localeUtil.getinput("server.login"))
        clog("Load ing...")

        setFilePath(if (args.isNotEmpty()) decodeString(args[0]) else null)

        Data.config = BaseConfig.stringToClass()
        Data.core.load()

        clog(Data.localeUtil.getinput("server.hi"))
        clog(Data.localeUtil.getinput("server.project.url"))

        /* 命令加载 */
        CoreCommands(Data.SERVER_COMMAND)
        ClientCommands(Data.CLIENT_COMMAND)
        LogCommands(Data.LOG_COMMAND)
        clog(Data.localeUtil.getinput("server.load.command"))

        /* Event加载 */
        add(Event())
        add(EventGlobal())
        clog(Data.localeUtil.getinput("server.load.events"))

        /* 初始化Plugin */
        init(getFolder(Data.Plugin_Plugins_Path))
        LoadCoreCustomPlugin()

        runOnEnable()
        runRegisterEvents()
        PluginManage.runRegisterCoreCommands(Data.SERVER_COMMAND)
        PluginManage.runRegisterClientCommands(Data.CLIENT_COMMAND)


        /* Core Net */
        loadNetCore()

        /* Load Save Unit */
        loadUnitList()

        /* 按键监听 */
        newThreadCore{ buttonMonitoring() }

        /* 加载完毕 */
        Events.fire(ServerLoadEvent())

        /* 初始化Plugin Init */
        runInit()
        clog(Data.localeUtil.getinput("server.load.end"))
        clog(Data.localeUtil.getinput("server.loadPlugin", loadSize))

        /* 默认直接启动服务器 */
        val response = Data.SERVER_COMMAND.handleMessage(Data.config.DefStartCommand, StrCons { obj: String -> clog(obj) })
        if (response != null && response.type != CommandHandler.ResponseType.noCommand) {
            if (response.type != CommandHandler.ResponseType.valid) {
                clog("Please check the command , Unable to use StartCommand inside Config to start the server")
            }
        }

        clog("Server Run PID : ${Data.core.pid}")
    }

    private fun buttonMonitoring() {
        val bufferedReader = System.console()
        if (bufferedReader == null) {
            Log.fatal("buttonMonitoring Null")
            return
        }
        while (true) {
            try {
                val str = bufferedReader.readLine()
                val response = Data.SERVER_COMMAND.handleMessage(str, StrCons { obj: String -> clog(obj) })
                if (response != null && response.type != CommandHandler.ResponseType.noCommand) {
                    if (response.type != CommandHandler.ResponseType.valid) {
                        val text = when (response.type) {
                            CommandHandler.ResponseType.manyArguments -> {
                                "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                            }
                            CommandHandler.ResponseType.fewArguments -> {
                                "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                            }
                            else -> {
                                "Unknown command. Check help"
                            }
                        }
                        clog(text)
                    }
                }
            } catch (e: Exception) {
                clog("ButtonMonitoring Error")
                info(e)
            }
        }
    }

    fun loadNetCore() {
        NetStaticData.protocolData.setNetConnectPacket(GameVersionPacket(), "2.0.0")
        try {
            val stream: GameOutputStream = Data.utilData
            stream.reset()
            stream.writeInt(1)
            val list = readFileListString(Objects.requireNonNull(
                Main::class.java.getResourceAsStream("/unitData-114")))
            stream.writeInt(list.size())
            var unitData: Array<String>
            for (str in list) {
                unitData = str.split("%#%").toTypedArray()
                stream.writeString(unitData[0])
                stream.writeInt(unitData[1].toInt())
                stream.writeBoolean(true)
                stream.writeBoolean(false)
                stream.writeLong(0)
                stream.writeLong(0)
            }
        } catch (e: Exception) {
            error(e)
        }
        clog("Load OK 1.14 Protocol")
    }

    fun loadUnitList() {
        if (Data.core.unitBase64.size() > 0) {
            try {
                //Data.utilData.buffer.reset();
                val stream: GameOutputStream = Data.utilData
                stream.reset()
                stream.writeInt(1)
                stream.writeInt(Data.core.unitBase64.size())
                var unitData: Array<String>
                for (str in Data.core.unitBase64) {
                    unitData = str.split("%#%").toTypedArray()
                    stream.writeString(unitData[0])
                    stream.writeInt(unitData[1].toInt())
                    stream.writeBoolean(true)
                    if (unitData.size > 2) {
                        stream.writeBoolean(true)
                        stream.writeString(unitData[2])
                    } else {
                        stream.writeBoolean(false)
                    }
                    stream.writeLong(0)
                    stream.writeLong(0)
                }
                clog("Load Mod Ok.")
            } catch (exp: Exception) {
                error("[Server] Load Setting Unit List Error", exp)
            }
        }
    }
}

// 你的身体是为你服务的 而不是RW-HPS(?)  !