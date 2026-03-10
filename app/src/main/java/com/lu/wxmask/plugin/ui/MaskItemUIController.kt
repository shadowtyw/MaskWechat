package com.lu.wxmask.plugin.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup.MarginLayoutParams.MATCH_PARENT
import android.view.ViewGroup.MarginLayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.core.view.setPadding
import com.lu.magic.util.SizeUtil
import com.lu.wxmask.Constrant
import com.lu.wxmask.bean.MaskItemBean
import com.lu.wxmask.ui.adapter.SpinnerListAdapter
import com.lu.wxmask.util.ext.dp

internal class MaskItemUIController(private val context: Context, private val mask: MaskItemBean) {
    private val viewId: MutableMap<String, View> = mutableMapOf()

    val dp24 = SizeUtil.dp2px(context.resources, 24f).toInt()
    var root: LinearLayout = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            MATCH_PARENT,
            WRAP_CONTENT
        ).also {
            orientation = LinearLayout.VERTICAL
        }
        setPadding(dp24)
    }
    
    var etMaskId: EditText = EditText(context).also {
        it.hint = "糊脸Id（需要伪装的真实好友微信ID）"
        it.setText(mask.maskId)
    }
    
    var etTagName = EditText(context).also {
        it.hint = "备注（自动填充，或自定义伪装显示的名字）"
        it.setText(mask.tagName)
    }
    
    var etTipMess = EditText(context).also {
        it.hint = "糊脸提示，如：${Constrant.WX_MASK_TIP_ALERT_MESS_DEFAULT}"
        when (mask.tipMode) {
            Constrant.WX_MASK_TIP_MODE_SILENT -> {
                it.setText(MaskItemBean.TipData.from(mask).mess)
                it.visibility = View.GONE
            }
            Constrant.CONFIG_TIP_MODE_ALERT -> it.setText(MaskItemBean.TipData.from(mask).mess)
            else -> it.setText(Constrant.WX_MASK_TIP_ALERT_MESS_DEFAULT)
        }
    }
    
    var etMapId = EditText(context).apply {
        hint = "变脸者ID（下拉选择自动填充，或手动输入）"
        setText(mask.mapId)
    }

    // ================== 新增：变脸快捷选择下拉菜单 ==================
    // 字典映射：左边是显示在菜单里的名字，右边是底层的真实微信号或控制指令
    private val officialAccountDict = listOf(
        "🔮 自动盲盒 (全自动智能分配)" to "",
        "🚫 完全隐藏 (主页不显示，仅红点提示)" to "hide_completely",
        "📁 文件传输助手" to "filehelper",
        "💰 微信支付" to "wxpayapp",
        "👥 微信团队" to "weixin",
        "🏃 微信运动" to "gh_43f2581f6fd6",
        "📰 订阅号消息" to "officialaccounts",
        "🔔 服务通知" to "notifymessage",
        "✍️ 手动自定义输入" to "custom"
    )

    var mapIdSpinner: Spinner = Spinner(context).also {
        it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        
        // 提取用于显示的名称列表
        val displayNames = officialAccountDict.map { pair -> pair.first }
        it.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, displayNames)

        // 初始化时，根据已保存的 mapId 自动选中对应的下拉项
        val initialIndex = officialAccountDict.indexOfFirst { pair -> pair.second == mask.mapId }
        if (initialIndex != -1) {
            it.setSelection(initialIndex)
        } else if (!mask.mapId.isNullOrEmpty()) {
            it.setSelection(officialAccountDict.size - 1) // 匹配不到说明是用户自定义的
        }

        // 下拉菜单选择事件联动
        it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = officialAccountDict[position]
                
                when (selectedItem.second) {
                    "custom" -> {
                        // 选择了手动输入，显示底层的输入框让用户自己填
                        etMapId.visibility = View.VISIBLE
                    }
                    "" -> {
                        // 选择了盲盒，清空两个框的内容交由底层代码自动分配，并隐藏变脸ID框保持清爽
                        etMapId.setText("")
                        etTagName.setText("")
                        etMapId.visibility = View.GONE
                    }
                    "hide_completely" -> {
                        // 选择了完全隐藏，下发特定指令并隐藏输入框
                        etMapId.setText("hide_completely")
                        etTagName.setText("完全隐藏")
                        etMapId.visibility = View.GONE 
                    }
                    else -> {
                        // 选择了具体的官方号，自动联动填入 ID 和 名字！
                        etMapId.setText(selectedItem.second)
                        // 把自带的表情符号去掉，只提取文字作为伪装名字
                        val cleanName = selectedItem.first.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9]"), "").trim()
                        etTagName.setText(cleanName)
                        etMapId.visibility = View.VISIBLE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    // ==============================================================

    val spinnerTipDataList = arrayListOf(
        Constrant.WX_MASK_TIP_MODE_SILENT to "静默模式",
        Constrant.CONFIG_TIP_MODE_ALERT to "提示模式"
    )
    
    var tipSpinner: Spinner = Spinner(context).also {
        it.layoutParams = LinearLayout.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT
        )
        it.adapter = SpinnerListAdapter(spinnerTipDataList)
        it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val itemData = spinnerTipDataList[position]
                when (itemData.first) {
                    Constrant.CONFIG_TIP_MODE_ALERT -> {
                        etTipMess.visibility = View.VISIBLE
                    }
                    Constrant.WX_MASK_TIP_MODE_SILENT -> {
                        etTipMess.visibility = View.GONE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    val tipSpinnerSelectedItem: Pair<Int, String>
        get() = spinnerTipDataList[tipSpinner.selectedItemPosition]

    init {
        LinearLayout.LayoutParams(
            MATCH_PARENT,
            WRAP_CONTENT
        ).apply {
            topMargin = 4.dp
        }
        
        // 按照逻辑顺序依次把控件添加到界面上
        root.addView(etMaskId, MATCH_PARENT, WRAP_CONTENT)
        root.addView(tipSpinner, MATCH_PARENT, WRAP_CONTENT)
        root.addView(etTipMess, MATCH_PARENT, WRAP_CONTENT)
        
        // 加入我们做好的变脸快捷选择下拉框
        root.addView(mapIdSpinner, MATCH_PARENT, WRAP_CONTENT)
        
        // 将备注名和变脸ID放在最后，方便观察联动填充的效果
        root.addView(etTagName, MATCH_PARENT, WRAP_CONTENT)
        root.addView(etMapId, MATCH_PARENT, WRAP_CONTENT)

        // 补刀：根据当前的 mask.mapId 强制初始化一次输入框的可见性，防止界面闪烁
        when (mask.mapId) {
            "hide_completely", "" -> etMapId.visibility = View.GONE
            else -> etMapId.visibility = View.VISIBLE
        }
    }
}
