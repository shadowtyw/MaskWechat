package com.lu.wxmask.plugin.part

import android.content.Context
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 隐藏朋友圈/亲密好友在会话列表中的显示
 */
class HideCloseFriendPluginPart : IPlugin {
    
    companion object {
        private var conversationAdapterRef: Any? = null
        private var conversationListViewRef: Any? = null
        private val hiddenCloseFriends = mutableSetOf<String>()
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val versionCode = AppVersionUtil.getVersionCode()
        
        if (versionCode >= Constrant.WX_CODE_8_0_70) {
            LogUtil.d("HideCloseFriendPluginPart: Using 8.0.70+ pipeline")
            handleWechat8070Pipeline(context, lpparam)
        } else {
            LogUtil.d("HideCloseFriendPluginPart: Using legacy pipeline")
            hookConversationQueries(context, lpparam)
        }
    }

    private fun handleWechat8070Pipeline(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        // 8.0.70+ 使用新的数据源 hook 方案
        hookWechat8070ConversationDataSourceBatch(context, lpparam)
        hookWechat8070ConversationDataSourceSingle(context, lpparam)
        hookWechat8070ConversationAdapterBind(context, lpparam)
    }

    /**
     * Hook 批量数据源更新
     */
    private fun hookWechat8070ConversationDataSourceBatch(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // DataSource 类名需要根据实际微信版本确定
            val dataSourceClazz = findDataSourceClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                dataSourceClazz,
                "updateData",
                List::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dataList = param.args[0] as? List<*> ?: return
                        LogUtil.d("wx8070-data-source-batch: ${dataList.size} items")
                        
                        val filteredList = filterConversationItems(dataList, context)
                        if (filteredList.size != dataList.size) {
                            param.args[0] = filteredList
                            LogUtil.d("wx8070-data-source-batch: filtered to ${filteredList.size} items")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationDataSourceBatch failed", it)
        }
    }

    /**
     * Hook 单条数据源更新
     */
    private fun hookWechat8070ConversationDataSourceSingle(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val dataSourceClazz = findDataSourceClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                dataSourceClazz,
                "onDataChanged",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result ?: return
                        val username = getConversationUsername(result, context)
                        
                        if (username != null && shouldHideConversation(username)) {
                            param.result = null
                            LogUtil.d("wx8070-data-source-single: hiding $username")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationDataSourceSingle failed", it)
        }
    }

    /**
     * Hook Adapter 绑定
     */
    private fun hookWechat8070ConversationAdapterBind(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Adapter 类名
            val adapterClazz = findConversationAdapterClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                adapterClazz,
                "onBindViewHolder",
                androidx.recyclerview.widget.RecyclerView.ViewHolder::class.java,
                Int::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val holder = param.args[0] ?: return
                        val position = param.args[1] as Int
                        
                        val username = getConversationUsernameFromMvvmItem(holder, context)
                        if (username != null && shouldHideConversation(username)) {
                            // 隐藏 itemView
                            try {
                                val itemView = XposedHelpers2.getObjectField<Any>(holder, "itemView")
                                itemView?.javaClass?.getMethod("setVisibility", Int::class.java)?.invoke(itemView, 8) // GONE
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationAdapterBind failed", it)
        }
    }

    /**
     * Hook 数据库查询
     */
    private fun hookConversationQueries(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Hook 数据库查询以过滤会话
            val dbHelperClazz = XposedHelpers2.findClass("com.tencent.mm.storage.BaseStorage", context.classLoader)
                ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                dbHelperClazz,
                "query",
                String::class.java,
                Array::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sql = param.args[0] as? String ?: return
                        if (needFilterConversationSql(sql)) {
                            val hiddenIds = getHiddenCloseFriendIds()
                            if (hiddenIds.isNotEmpty()) {
                                // 注入过滤条件
                                val filterClause = hiddenIds.joinToString(",") { "'$it'" }
                                val newSql = sql.replace(
                                    "WHERE",
                                    "WHERE field_username NOT IN ($filterClause) AND "
                                )
                                param.args[0] = newSql
                                LogUtil.d("hookConversationQueries: injected filter")
                            }
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookConversationQueries failed", it)
        }
    }

    /**
     * 过滤会话列表项
     */
    fun filterConversationItems(items: List<*>, context: Context): List<Any> {
        val option = ConfigUtil.getOptionData()
        if (!option.hideCloseFriend) {
            return items.filterNotNull()
        }
        
        return items.filterNotNull().filter { item ->
            val username = getConversationUsernameFromMvvmItem(item, context)
            username == null || !shouldHideConversation(username)
        }
    }

    /**
     * 判断是否需要过滤 SQL
     */
    fun needFilterConversationSql(sql: String): Boolean {
        return sql.contains("rconversation", ignoreCase = true) &&
               sql.contains("SELECT", ignoreCase = true) &&
               sql.contains("field_username", ignoreCase = true)
    }

    /**
     * 判断是否应该隐藏会话
     */
    fun shouldHideConversation(username: String): Boolean {
        val option = ConfigUtil.getOptionData()
        if (!option.hideCloseFriend) {
            return false
        }
        return WXMaskPlugin.getCloseFriendIds().contains(username)
    }

    /**
     * 请求刷新会话列表
     */
    fun requestRefreshVisibleConversationList(context: Context) {
        runCatching {
            conversationAdapterRef?.let { adapter ->
                adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            }
        }.onFailure {
            LogUtil.w("requestRefreshVisibleConversationList failed", it)
        }
    }

    // ==================== 辅助方法 ====================

    private fun findDataSourceClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.adapter.ConversationDataSource")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.MvvmConvList")
        }.getOrNull()
    }

    private fun findConversationAdapterClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.ConversationAdapter")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.adapter.ConversationAdapter")
        }.getOrNull()
    }

    private fun getConversationUsername(itemData: Any, context: Context): String? {
        return runCatching {
            XposedHelpers2.getObjectField<String?>(itemData, "field_username")
        }.getOrNull()
    }

    private fun getConversationUsernameFromMvvmItem(item: Any, context: Context): String? {
        return runCatching {
            // 尝试从 MVVM item 中提取用户名
            val container = XposedHelpers2.getObjectField<Any?>(item, "container") ?: item
            XposedHelpers2.getObjectField<String?>(container, "field_username")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<String?>(item, "field_username")
        }.getOrNull()
    }

    private fun getHiddenCloseFriendIds(): Set<String> {
        return WXMaskPlugin.getCloseFriendIds()
    }

    // Setter for conversation adapter reference
    fun setConversationAdapterRef(adapter: Any?) {
        conversationAdapterRef = adapter
    }

    fun setConversationListViewRef(listView: Any?) {
        conversationListViewRef = listView
    }
}
