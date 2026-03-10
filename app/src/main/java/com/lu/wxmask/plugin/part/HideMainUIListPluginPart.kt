package com.lu.wxmask.plugin.part

import android.content.Context
import android.graphics.Bitmap.Config
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.GsonUtil
import com.lu.magic.util.ResUtil
import com.lu.magic.util.log.LogUtil
import com.lu.magic.util.view.ChildDeepCheck
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXConfigPlugin
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.plugin.ui.MaskUtil
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * 主页UI处理：毕业级终极智能版
 */
class HideMainUIListPluginPart : IPlugin {
    
    // ================== 智能官方号全套字典 ==================
    private val officialAccountDict = mapOf(
        "weixin" to "微信团队",
        "officialaccounts" to "订阅号消息",
        "gh_43f2581f6fd6" to "微信运动",
        "filehelper" to "文件传输助手",
        "wxpayapp" to "微信支付",
        "notifymessage" to "服务通知"
    )

    // 全自动盲盒分配系统
    private fun getAutoTarget(realWxid: String): Pair<String, String> {
        val keys = officialAccountDict.keys.toList()
        val index = Math.abs(realWxid.hashCode()) % keys.size
        val targetId = keys[index]
        return Pair(targetId, officialAccountDict[targetId]!!)
    }
    // =========================================================

    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k" 
        Constrant.WX_CODE_PLAY_8_0_48 -> "l"
        Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51,  Constrant.WX_CODE_8_0_56 -> "l"
        Constrant.WX_CODE_8_0_50 -> "n"
        Constrant.WX_CODE_8_0_53 -> "m"
        else -> "m"
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            hookListViewClick()
        }.onFailure {
            LogUtil.w("hookListViewClick fail", it)
        }

        runCatching {
            handleMainUIChattingListView2(context, lpparam)
        }.onFailure {
            LogUtil.w("hide mainUI listview fail, try to old function.")
            handleMainUIChattingListView(context, lpparam)
        }
    }

    private fun hookListViewClick() {
        XposedHelpers2.findAndHookMethod(
            android.widget.AdapterView::class.java,
            "performItemClick",
            View::class.java,
            java.lang.Integer.TYPE,
            java.lang.Long.TYPE,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listView = param.thisObject as android.widget.AdapterView<*>
                    val position = param.args[1] as Int
                    val itemData = listView.getItemAtPosition(position) ?: return
                    
                    if (!itemData::class.java.name.contains("storage") && !itemData::class.java.name.contains("Conversation")) return
                    val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return

                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        val option = ConfigUtil.getOptionData()
                        if (option.enableMapConversation) {
                            val maskBean = WXMaskPlugin.getMaskBeamById(chatUser)
                            if (maskBean != null) {
                                param.setObjectExtra("real_wxid", chatUser)
                                // 智能取ID：填了用填的，没填用盲盒的
                                val targetId = if (maskBean.mapId.isNullOrBlank()) getAutoTarget(chatUser).first else maskBean.mapId
                                XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                            }
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val realWxid = param.getObjectExtra("real_wxid") as? String
                    if (realWxid != null) {
                        val listView = param.thisObject as android.widget.AdapterView<*>
                        val position = param.args[1] as Int
                        val itemData = listView.getItemAtPosition(position) ?: return
                        XposedHelpers2.setObjectField(itemData, "field_username", realWxid)
                    }
                }
            }
        )
    }

    private fun handleMainUIChattingListView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.conversation.k"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> {
                if (AppVersionUtil.getVersionName() == "8.0.35") {
                    "com.tencent.mm.ui.conversation.r"
                } else {
                    "com.tencent.mm.ui.conversation.p"
                }
            }
            Constrant.WX_CODE_8_0_35 -> "com.tencent.mm.ui.conversation.r"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_41 -> "com.tencent.mm.ui.conversation.x" 
            Constrant.WX_CODE_8_0_47 -> "com.tencent.mm.ui.conversation.p3"
            Constrant.WX_CODE_8_0_50 -> "com.tencent.mm.ui.conversation.q3"
            else -> null
        }
        var adapterClazz: Class<*>? = null
        if (adapterName != null) {
            adapterClazz = ClazzN.from(adapterName, context.classLoader)
        }
        if (adapterClazz != null) {
            hookListViewAdapter(adapterClazz)
        } else {
            val setAdapterMethod = XposedHelpers2.findMethodExactIfExists(
                ListView::class.java.name,
                context.classLoader,
                "setAdapter",
                ListAdapter::class.java
            )
            if (setAdapterMethod == null) return
            XposedHelpers2.hookMethod(
                setAdapterMethod,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapter = param.args[0] ?: return
                        if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                            hookListViewAdapter(adapter.javaClass)
                        }
                    }
                }
            )
        }
    }

    private fun hookListViewAdapter(adapterClazz: Class<*>) {
        var getViewMethod: Method? = null
        var currentClass: Class<*>? = adapterClazz
        while (currentClass != null && currentClass != Any::class.java) {
            getViewMethod = XposedHelpers2.findMethodExactIfExists(
                currentClass,
                "getView",
                java.lang.Integer.TYPE,
                View::class.java,
                ViewGroup::class.java
            )
            if (getViewMethod != null) break
            currentClass = currentClass.superclass
        }

        if (getViewMethod == null) return

        val getViewMethodIDText = getViewMethod.toString()
        if (MainHook.uniqueMetaStore.contains(getViewMethodIDText)) return
        
        XposedHelpers2.hookMethod(
            getViewMethod,
            object : XC_MethodHook2() {

                override fun beforeHookedMethod(param: MethodHookParam) {
                    val adapter = param.thisObject as ListAdapter
                    val position = (param.args[0] as? Int?) ?: return
                    val itemData = adapter.getItem(position) ?: return
                    val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return

                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        val option = ConfigUtil.getOptionData()
                        if (option.enableMapConversation) {
                            val maskBean = WXMaskPlugin.getMaskBeamById(chatUser)
                            if (maskBean != null) {
                                param.setObjectExtra("real_wxid", chatUser)
                                val targetId = if (maskBean.mapId.isNullOrBlank()) getAutoTarget(chatUser).first else maskBean.mapId
                                XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                            }
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter: ListAdapter = param.thisObject as ListAdapter
                    val position: Int = (param.args[0] as? Int?) ?: return
                    val itemData: Any = adapter.getItem(position) ?: return
                    val itemView: View = param.args[1] as? View ?: return

                    val realWxid = param.getObjectExtra("real_wxid") as? String

                    if (realWxid != null) {
                        XposedHelpers2.setObjectField(itemData, "field_username", realWxid)

                        val maskBean = WXMaskPlugin.getMaskBeamById(realWxid)
                        if (maskBean != null) {
                            val nameTvIdName = when (AppVersionUtil.getVersionCode()) {
                                Constrant.WX_CODE_8_0_69 -> "kbq" 
                                else -> "kbq" 
                            }
                            val nameViewId = ResUtil.getViewId(nameTvIdName)
                            if (nameViewId != 0 && nameViewId != View.NO_ID) {
                                try {
                                    val nameTv: View? = itemView.findViewById(nameViewId)
                                    if (nameTv != null) {
                                        // 【智能名称判断核心】：
                                        val customName = if (!maskBean.tagName.isNullOrBlank()) {
                                            // 1. 如果你手填了备注，优先级最高，绝对服从
                                            maskBean.tagName
                                        } else if (!maskBean.mapId.isNullOrBlank()) {
                                            // 2. 如果你只填了变脸ID（没填备注），自动查字典补全名字
                                            officialAccountDict[maskBean.mapId] ?: "未知联系人"
                                        } else {
                                            // 3. 啥都没填，全自动盲盒分配
                                            getAutoTarget(realWxid).second
                                        }
                                        XposedHelpers2.callMethod<Any?>(nameTv, "setText", customName)
                                    }
                                } catch (e: Throwable) {
                                    LogUtil.w("修改名字失败", e)
                                }
                            }
                        }

                        hideUnReadTipView(itemView, param)
                        hideMsgViewItemText(itemView, param)
                        
                    } else {
                        val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return
                        if (WXMaskPlugin.containChatUser(chatUser)) {
                            hideUnReadTipView(itemView, param)
                            hideMsgViewItemText(itemView, param)
                        }
                    }
                }

                private fun hideUnReadTipView(itemView: View, param: MethodHookParam) {
                    val tipTvIdTextID = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "tipcnt_tv"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "oqu"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_41 -> "kmv"
                        else -> "kmv"
                    }
                    val tipTvId = ResUtil.getViewId(tipTvIdTextID)
                    itemView.findViewById<View>(tipTvId)?.visibility = View.INVISIBLE

                    val small_red = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_40 -> "a2f"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "a_w"
                        Constrant.WX_CODE_8_0_41 -> "o_u"
                        else -> "o_u"
                    }
                    val viewId = ResUtil.getViewId(small_red)
                    itemView.findViewById<View>(viewId)?.visibility = View.INVISIBLE
                }

                private fun hideMsgViewItemText(itemView: View, param: MethodHookParam) {
                    val msgTvIdName = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "last_msg_tv"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_40 -> "fhs"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "i2_"
                        Constrant.WX_CODE_8_0_41 -> "ht5"
                        else -> "ht5" 
                    }
                    val lastMsgViewId = ResUtil.getViewId(msgTvIdName)
                    if (lastMsgViewId != 0 && lastMsgViewId != View.NO_ID) {
                        try {
                            val msgTv: View? = itemView.findViewById(lastMsgViewId)
                            XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                        } catch (e: Throwable) {}
                    } else {
                        val ClazzNoMeasuredTextView = ClazzN.from("com.tencent.mm.ui.base.NoMeasuredTextView")
                        ChildDeepCheck().each(itemView) { child ->
                            try {
                                if (ClazzNoMeasuredTextView?.isAssignableFrom(child::class.java) == true
                                    || TextView::class.java.isAssignableFrom(child::class.java)
                                ) {
                                    XposedHelpers2.callMethod<String?>(child, "setText", "")
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                }
            })
        MainHook.uniqueMetaStore.add(getViewMethodIDText)
    }

    private fun findGetItemMethod(adapterClazz: Class<*>?): Method? {
        if (adapterClazz == null) return null
        var method: Method? = XposedHelpers2.findMethodExactIfExists(adapterClazz, GetItemMethodName, Integer.TYPE)
        if (method != null) return method
        
        var methods = XposedHelpers2.findMethodsByExactPredicate(adapterClazz) { m ->
            val ret = !arrayOf(
                Object::class.java, String::class.java, Byte::class.java, Short::class.java,
                Long::class.java, Float::class.java, Double::class.java, java.lang.Byte.TYPE,
                java.lang.Short.TYPE, java.lang.Integer.TYPE, java.lang.Long.TYPE,
                java.lang.Float.TYPE, java.lang.Double.TYPE, java.lang.Void.TYPE
            ).contains(m.returnType)
            val paramVail = m.parameterTypes.size == 1 && m.parameterTypes[0] == Integer.TYPE
            return@findMethodsByExactPredicate paramVail && ret && Modifier.isPublic(m.modifiers) && !Modifier.isAbstract(m.modifiers)
        }
        if (methods.size > 0) {
            method = methods[0]
        }
        return method
    }

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56,-> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_69 -> "o75.v0" 
            else -> null
        }
        
        var adapterClazz = if (adapterClazzName != null) ClazzN.from(adapterClazzName) else null

        if (adapterClazz != null) {
            var getItemMethod = findGetItemMethod(adapterClazz)
            if (getItemMethod != null) {
                hookListViewGetItem(getItemMethod)
            }
            hookListViewAdapter(adapterClazz)
            return
        }

        XposedHelpers2.findAndHookMethod(
            ListView::class.java,
            "setAdapter",
            ListAdapter::class.java,
            object : XC_MethodHook2() {
                private var isHookGetItemMethod = false
                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter = param.args[0] ?: return
                    if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                        if (isHookGetItemMethod) return
                        var getItemMethod = findGetItemMethod(adapter::class.java.superclass)
                        if (getItemMethod == null) {
                            getItemMethod = XposedHelpers2.findMethodExactIfExists(adapter::class.java.superclass, "getItem", Integer.TYPE)
                        }
                        if (getItemMethod != null) {
                            hookListViewGetItem(getItemMethod)
                            isHookGetItemMethod = true
                        }
                    }
                }
            }
        )
    }

    private fun hookListViewGetItem(getItemMethod: Method) {
        XposedHelpers2.hookMethod(
            getItemMethod,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemData: Any = param.result ?: return
                    val chatUser: String? = XposedHelpers2.getObjectField(itemData, "field_username")
                    if (chatUser == null) return

                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        val option = ConfigUtil.getOptionData()
                        XposedHelpers2.setObjectField(itemData, "field_content", "")
                        XposedHelpers2.setObjectField(itemData, "field_digest", "")
                        XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
                        XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
                        XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
                        XposedHelpers2.setObjectField(itemData, "field_msgType", "1")

                        if (option.enableTravelTime && option.travelTime != 0L) {
                            val cTime = XposedHelpers2.getObjectField<Any>(itemData, "field_conversationTime")
                            if (cTime is Long) {
                                XposedHelpers2.setObjectField(itemData, "field_conversationTime", cTime - option.travelTime)
                            }
                        }
                    }
                }
            }
        )
    }
}
