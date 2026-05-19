package com.lu.wxmask.plugin.part

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import androidx.recyclerview.widget.RecyclerView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.GsonUtil
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.BuildConfig
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 隐藏通讯录中指定联系人
 */
class HideContactFriendPluginPart : IPlugin {
    
    companion object {
        private var addressAdapterRef: Any? = null
        private var addressListViewRef: Any? = null
        private var searchResultAdapterRef: Any? = null
        private var searchResultListViewRef: Any? = null
        private val hiddenContactIds = mutableSetOf<String>()
        private var suppressAddressLifecycleRefresh = false
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val versionCode = AppVersionUtil.getVersionCode()
        
        if (versionCode >= Constrant.WX_CODE_8_0_70) {
            LogUtil.d("HideContactFriendPluginPart: Using 8.0.70+ pipeline")
            handleWechat8070AddressPipeline(context, lpparam)
        } else {
            LogUtil.d("HideContactFriendPluginPart: Using legacy pipeline")
            hookAddressAdapter(context, lpparam)
        }
    }

    private fun handleWechat8070AddressPipeline(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook RecyclerView Adapter
        hookMvvmAddressRecyclerAdapter(context, lpparam)
        // Hook AddressLiveList
        hookAddressLiveList(context, lpparam)
        // Hook Contact Database
        hookContactDatabase(context, lpparam)
        // Hook Search Result
        hookSearchResultAdapterClass(context, lpparam)
        hookSearchResultListLayout(context, lpparam)
        // Hook Fragment Lifecycle
        hookSupportFragmentLifecycle(context, lpparam)
    }

    /**
     * Hook MVVM 地址列表 RecyclerView Adapter
     */
    private fun hookMvvmAddressRecyclerAdapter(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val adapterClazz = findMvvmAddressAdapterClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                adapterClazz,
                "onBindViewHolder",
                RecyclerView.ViewHolder::class.java,
                Int::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val holder = param.args[0] ?: return
                        val position = param.args[1] as Int
                        
                        if (isHiddenMvvmAddressItem(holder, context)) {
                            hideRow(holder)
                        }
                    }
                }
            )
            LogUtil.d("hookMvvmAddressRecyclerAdapter: success")
        }.onFailure {
            LogUtil.w("hookMvvmAddressRecyclerAdapter failed", it)
        }
    }

    /**
     * Hook AddressLiveList
     */
    private fun hookAddressLiveList(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val liveListClazz = XposedHelpers2.findClass(
                "com.tencent.mm.ui.contact.address.AddressLiveList",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                liveListClazz,
                "updateData",
                List::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val items = param.args[0] as? List<*> ?: return
                        val filtered = filterAddressSourceItems(items, context)
                        if (filtered.size != items.size) {
                            param.args[0] = filtered
                            LogUtil.d("hookAddressLiveList: filtered ${items.size} -> ${filtered.size}")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookAddressLiveList failed", it)
        }
    }

    /**
     * Hook Contact Database
     */
    private fun hookContactDatabase(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Hook 数据库查询以注入隐藏条件
            val storageClazz = XposedHelpers2.findClass(
                "com.tencent.mm.storage.BaseStorage",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                storageClazz,
                "query",
                String::class.java,
                Array::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sql = param.args[0] as? String ?: return
                        if (needFilterContactSql(sql)) {
                            val hiddenIds = getActiveHiddenIds()
                            if (hiddenIds.isNotEmpty()) {
                                val newSql = buildHiddenContactSql(sql, hiddenIds)
                                param.args[0] = newSql
                            }
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookContactDatabase failed", it)
        }
    }

    /**
     * Hook Search Result Adapter
     */
    private fun hookSearchResultAdapterClass(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val adapterClazz = findSearchResultAdapterClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                adapterClazz,
                "getView",
                Int::class.java,
                View::class.java,
                ViewGroup::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val position = param.args[0] as Int
                        val adapter = param.thisObject as BaseAdapter
                        val item = adapter.getItem(position) ?: return
                        
                        val username = getContactUsernameStrict(item, context)
                        if (username != null && isContactHideActive(username)) {
                            // 返回一个空的 View
                            val parent = param.args[2] as? ViewGroup
                            param.result = View(parent?.context)
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookSearchResultAdapterClass failed", it)
        }
    }

    /**
     * Hook Search Result List Layout
     */
    private fun hookSearchResultListLayout(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Hook 搜索结果列表的布局填充
            val layoutClazz = XposedHelpers2.findClass(
                "com.tencent.mm.ui.contact.SearchResultUI",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                layoutClazz,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject
                        val listView = findRecyclerViewFromRootView(activity, context)
                        if (listView != null) {
                            searchResultListViewRef = listView
                            LogUtil.d("hookSearchResultListLayout: found RecyclerView")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookSearchResultListLayout failed", it)
        }
    }

    /**
     * Hook Fragment Lifecycle
     */
    private fun hookSupportFragmentLifecycle(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val fragmentClazz = XposedHelpers2.findClass(
                "com.tencent.mm.ui.contact.AddressUI\$AddressUIFragment",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                fragmentClazz,
                "onResume",
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        suppressAddressLifecycleRefresh = true
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        suppressAddressLifecycleRefresh = false
                        refreshAddressListFromFragment(param.thisObject, context)
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookSupportFragmentLifecycle failed", it)
        }
    }

    /**
     * Hook WxRecyclerView Bind
     */
    private fun hookWxRecyclerViewBind(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val recyclerViewClazz = XposedHelpers2.findClass(
                "com.tencent.mm.view.recyclerview.WxRecyclerView",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                recyclerViewClazz,
                "setAdapter",
                RecyclerView.Adapter::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapter = param.args[0]
                        addressAdapterRef = adapter
                        LogUtil.d("hookWxRecyclerViewBind: adapter set")
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWxRecyclerViewBind failed", it)
        }
    }

    /**
     * Legacy: Hook Address Adapter
     */
    private fun hookAddressAdapter(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val adapterClazz = findAddressAdapterClazz(context) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                adapterClazz,
                "getView",
                Int::class.java,
                View::class.java,
                ViewGroup::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val position = param.args[0] as Int
                        val adapter = param.thisObject as BaseAdapter
                        val item = adapter.getItem(position) ?: return
                        
                        val username = getContactUsernameStrict(item, context)
                        if (username != null && isContactHideActive(username)) {
                            val parent = param.args[2] as? ViewGroup
                            param.result = View(parent?.context)
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookAddressAdapter failed", it)
        }
    }

    // ==================== 过滤和处理方法 ====================

    /**
     * 过滤通讯录列表项
     */
    fun filterAddressSourceItems(items: List<*>, context: Context): List<Any> {
        return items.filterNotNull().filter { item ->
            val username = getAddressUsername(item, context)
            username == null || !isContactHideActive(username)
        }
    }

    /**
     * 构建隐藏联系人的 SQL
     */
    fun buildHiddenContactSql(sql: String, hiddenIds: Set<String>): String {
        if (hiddenIds.isEmpty()) return sql
        val filterClause = hiddenIds.joinToString(",") { "'$it'" }
        return sql.replace(
            Regex("WHERE\\s+", RegexOption.IGNORE_CASE),
            "WHERE username NOT IN ($filterClause) AND "
        )
    }

    /**
     * 判断是否需要过滤联系人 SQL
     */
    fun needFilterContactSql(sql: String): Boolean {
        return sql.contains("contact", ignoreCase = true) &&
               sql.contains("SELECT", ignoreCase = true) &&
               sql.contains("username", ignoreCase = true)
    }

    /**
     * 判断联系人隐藏是否激活
     */
    fun isContactHideActive(username: String): Boolean {
        val option = ConfigUtil.getOptionData()
        return option.hideContact && WXMaskPlugin.getContactHideIds().contains(username)
    }

    /**
     * 判断是否为通讯录 Adapter
     */
    fun isAddressAdapter(adapter: Any): Boolean {
        val className = adapter::class.java.name
        return className.contains("AddressAdapter") ||
               className.contains("ContactAdapter") ||
               className.contains("address.adapter")
    }

    /**
     * 隐藏行
     */
    fun hideRow(holder: Any) {
        runCatching {
            val itemView = XposedHelpers2.getObjectField<View>(holder, "itemView") ?: return@runCatching
            itemView.visibility = View.GONE
            val lp = itemView.layoutParams
            lp?.let {
                if (it is ViewGroup.LayoutParams) {
                    it.height = 1
                    itemView.layoutParams = it
                }
            }
        }.onFailure {
            // ignore
        }
    }

    /**
     * 判断是否为隐藏的 MVVM 地址项
     */
    fun isHiddenMvvmAddressItem(holder: Any, context: Context): Boolean {
        val username = getMvvmAddressItemUsername(holder, context) ?: return false
        return isContactHideActive(username)
    }

    /**
     * 请求刷新可见联系人列表
     */
    fun requestRefreshVisibleContactList(context: Context) {
        runCatching {
            addressAdapterRef?.let { adapter ->
                if (adapter is RecyclerView.Adapter<*>) {
                    adapter.notifyDataSetChanged()
                } else if (adapter is BaseAdapter) {
                    adapter.notifyDataSetChanged()
                }
            }
        }.onFailure {
            LogUtil.w("requestRefreshVisibleContactList failed", it)
        }
    }

    /**
     * 刷新通讯录列表
     */
    private fun refreshAddressListFromFragment(fragment: Any, context: Context) {
        runCatching {
            val listView = findRecyclerViewFromObject(fragment, context)
            listView?.let {
                val adapter = it.adapter
                adapter?.notifyDataSetChanged()
            }
        }.onFailure {
            LogUtil.w("refreshAddressListFromFragment failed", it)
        }
    }

    // ==================== 辅助查找方法 ====================

    private fun findMvvmAddressAdapterClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.adapter.MvvmAddressAdapter")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.AddressAdapter")
        }.getOrNull()
    }

    private fun findAddressAdapterClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.AddressAdapter")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.a")
        }.getOrNull()
    }

    private fun findSearchResultAdapterClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.SearchResultAdapter")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.contact.b")
        }.getOrNull()
    }

    private fun getAddressUsername(item: Any, context: Context): String? {
        return runCatching {
            XposedHelpers2.getObjectField<String?>(item, "field_username")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<String?>(item, "username")
        }.getOrNull() ?: runCatching {
            val contact = XposedHelpers2.getObjectField<Any?>(item, "contact") ?: return@runCatching null
            XposedHelpers2.getObjectField<String?>(contact, "field_username")
        }.getOrNull()
    }

    private fun getContactUsernameStrict(item: Any, context: Context): String? {
        return runCatching {
            XposedHelpers2.getObjectField<String?>(item, "field_username")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<String?>(item, "username")
        }.getOrNull()
    }

    private fun getMvvmAddressItemUsername(holder: Any, context: Context): String? {
        return runCatching {
            val item = XposedHelpers2.getObjectField<Any?>(holder, "item") ?: return@runCatching null
            getAddressUsername(item, context)
        }.getOrNull()
    }

    private fun getActiveHiddenIds(): Set<String> {
        return WXMaskPlugin.getContactHideIds()
    }

    private fun findRecyclerViewFromRootView(obj: Any, context: Context): RecyclerView? {
        return runCatching {
            val view = XposedHelpers2.getObjectField<View>(obj, "mView") ?: return@runCatching null
            findRecyclerViewInTree(view)
        }.getOrNull()
    }

    private fun findRecyclerViewFromObject(obj: Any, context: Context): RecyclerView? {
        return runCatching {
            if (obj is RecyclerView) return obj
            val fields = obj::class.java.declaredFields
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(obj) ?: continue
                if (value is RecyclerView) return value
            }
            null
        }.getOrNull()
    }

    private fun findRecyclerViewInTree(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findRecyclerViewInTree(child)
                if (result != null) return result
            }
        }
        return null
    }

    // Setters
    fun setAddressAdapterRef(adapter: Any?) {
        addressAdapterRef = adapter
    }

    fun setAddressListViewRef(listView: Any?) {
        addressListViewRef = listView
    }

    fun setSearchResultAdapterRef(adapter: Any?) {
        searchResultAdapterRef = adapter
    }

    fun setSearchResultListViewRef(listView: Any?) {
        searchResultListViewRef = listView
    }
}
