package com.lu.wxmask.plugin.part

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.ResUtil
import com.lu.magic.util.log.LogUtil
import com.lu.magic.util.view.ChildDeepCheck
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.ext.getViewId  // <-- 就是漏了这一行
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 主页UI处理：全自动随机智能变脸最终完美版 (已修复延迟闪烁与生命周期失效问题)
 */
class HideMainUIListPluginPart : IPlugin {

    companion object {
        // 【核心优化点】使用 WeakHashMap 绑定底层数据对象与真实wxid。
        // 这样既防止了内存泄漏，又解决了微信异步渲染导致的闪烁，以及Adapter刷新/对象复用导致的失效问题。
        private val itemDataRealWxidMap = WeakHashMap<Any, String>()
    }

    // ================== 全自动官方号盲盒系统 ==================
    private fun getAutoTarget(realWxid: String): Pair<String, String> {
        val officialAccounts = listOf(
            Pair("weixin", "微信团队"),
            Pair("officialaccounts", "订阅号消息"),
            Pair("gh_43f2581f6fd6", "微信运动"),
            Pair("filehelper", "文件传输助手"),
            Pair("wxpayapp", "微信支付"),
            Pair("notifymessage", "服务通知")
        )
        val index = Math.abs(realWxid.hashCode()) % officialAccounts.size
        return officialAccounts[index]
    }
    // ==============================================================

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
                    
                    // 【核心优化点】直接从 WeakHashMap 获取真实 wxid。哪怕 field_username 被替换成了伪装ID，这里也能找回真实身份
                    val realWxid = itemDataRealWxidMap[itemData] ?: return

                    // 点击瞬间，将真实 wxid 塞回数据对象，骗过微信内部的跳转逻辑
                    XposedHelpers2.setObjectField(itemData, "field_username", realWxid)
                    param.setObjectExtra("restored_wxid", realWxid)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val realWxid = param.getObjectExtra("restored_wxid") as? String ?: return
                    val listView = param.thisObject as android.widget.AdapterView<*>
                    val position = param.args[1] as Int
                    val itemData = listView.getItemAtPosition(position) ?: return
                    
                    // 跳转逻辑执行完后，立即把 ID 变回伪装 ID，保证退回主界面时 UI 不穿帮
                    val maskBean = WXMaskPlugin.getMaskBeamById(realWxid)
                    val targetId = if (maskBean?.mapId.isNullOrBlank()) getAutoTarget(realWxid).first else maskBean!!.mapId
                    XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                }
            }
        )
    }

    private fun handleMainUIChattingListView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.conversation.k"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> {
                if (AppVersionUtil.getVersionName() == "8.0.35") "com.tencent.mm.ui.conversation.r" else "com.tencent.mm.ui.conversation.p"
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

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56 -> "com.tencent.mm.ui.i3"
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
                    val currentUsername = XposedHelpers2.getObjectField(itemData, "field_username") as? String ?: return
                    var realWxid = currentUsername

                    // 【核心优化点】处理对象复用与数据重载
                    if (itemDataRealWxidMap.containsKey(itemData)) {
                        val mappedWxid = itemDataRealWxidMap[itemData]!!
                        val maskBean = WXMaskPlugin.getMaskBeamById(mappedWxid)
                        val targetId = if (maskBean?.mapId.isNullOrBlank()) getAutoTarget(mappedWxid).first else maskBean!!.mapId
                        
                        if (currentUsername == targetId) {
                            // 依然是我们的伪装对象
                            realWxid = mappedWxid
                        } else if (currentUsername != mappedWxid) {
                            // 微信底层的 WCDB 将此数据对象回收利用分配给了别人，清除旧缓存！
                            itemDataRealWxidMap.remove(itemData)
                        }
                    }

                    if (WXMaskPlugin.containChatUser(realWxid)) {
                        val option = ConfigUtil.getOptionData()
                        
                        // 1. 建立弱引用绑定，保存真实身份
                        itemDataRealWxidMap[itemData] = realWxid

                        // 2. 彻底替换底层数据的 username。这步做完，微信所有的异步渲染都会自动加载官方号头像，无任何延迟闪烁！
                        if (option.enableMapConversation) {
                            val maskBean = WXMaskPlugin.getMaskBeamById(realWxid)
                            val targetId = if (maskBean?.mapId.isNullOrBlank()) getAutoTarget(realWxid).first else maskBean!!.mapId
                            XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                        }

                        // 3. 抹除消息痕迹
                        XposedHelpers2.setObjectField(itemData, "field_content", "")
                        XposedHelpers2.setObjectField(itemData, "field_digest", "")
                        XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
                        XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
                        XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
                        XposedHelpers2.setObjectField(itemData, "field_msgType", "1")

                        // 4. 时空穿梭
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
                // 不再在 beforeHookedMethod 中修改任何数据！避免与 getItem 打架

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter: ListAdapter = param.thisObject as ListAdapter
                    val position: Int = (param.args[0] as? Int?) ?: return
                    val itemData: Any = adapter.getItem(position) ?: return
                    val itemView: View = param.args[1] as? View ?: return

                    // 安全获取真实 wxid
                    val currentUsername = XposedHelpers2.getObjectField(itemData, "field_username") as? String
                    val realWxid = itemDataRealWxidMap[itemData] ?: if (WXMaskPlugin.containChatUser(currentUsername ?: "")) currentUsername else null

                    if (realWxid != null) {
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
                                        val customName = if (maskBean.tagName.isNullOrBlank() && maskBean.mapId.isNullOrBlank()) {
                                            getAutoTarget(realWxid).second
                                        } else if (!maskBean.tagName.isNullOrBlank()) {
                                            maskBean.tagName
                                        } else {
                                            "文件传输助手"
                                        }
                                        XposedHelpers2.callMethod<Any?>(nameTv, "setText", customName)
                                    }
                                } catch (e: Throwable) {
                                    LogUtil.w("修改名字失败", e)
                                }
                            }
                        }

                        // 强制隐藏红点和预览文字
                        hideUnReadTipView(itemView)
                        hideMsgViewItemText(itemView)
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

    // ========== 封装的 UI 隐藏辅助方法 ==========
    private fun hideUnReadTipView(itemView: View) {
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

    private fun hideMsgViewItemText(itemView: View) {
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
}
