package com.idormy.sms.forwarder.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.adapter.ItemMoveCallback
import com.idormy.sms.forwarder.adapter.TaskSettingAdapter
import com.idormy.sms.forwarder.adapter.WidgetItemAdapter
import com.idormy.sms.forwarder.core.BaseFragment
import com.idormy.sms.forwarder.database.AppDatabase
import com.idormy.sms.forwarder.database.entity.Task
import com.idormy.sms.forwarder.database.viewmodel.BaseViewModelFactory
import com.idormy.sms.forwarder.database.viewmodel.TaskViewModel
import com.idormy.sms.forwarder.databinding.FragmentTasksEditBinding
import com.idormy.sms.forwarder.entity.task.CronSetting
import com.idormy.sms.forwarder.entity.task.TaskSetting
import com.idormy.sms.forwarder.utils.*
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xpage.base.XPageFragment
import com.xuexiang.xpage.core.PageOption
import com.xuexiang.xpage.model.PageInfo
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xui.adapter.recyclerview.RecyclerViewHolder
import com.xuexiang.xui.utils.DensityUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.actionbar.TitleBar
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.*
import java.util.*


@Page(name = "自动任务·编辑器")
@Suppress("PrivatePropertyName", "unused", "DEPRECATION", "UNUSED_PARAMETER")
class TasksEditFragment : BaseFragment<FragmentTasksEditBinding?>(), View.OnClickListener, RecyclerViewHolder.OnItemClickListener<PageInfo> {

    private val TAG: String = TasksEditFragment::class.java.simpleName
    private val that = this
    var titleBar: TitleBar? = null
    private val viewModel by viewModels<TaskViewModel> { BaseViewModelFactory(context) }
    private val dialog: BottomSheetDialog by lazy {
        BottomSheetDialog(requireContext())
    }

    @JvmField
    @AutoWired(name = KEY_TASK_ID)
    var taskId: Long = 0

    @JvmField
    @AutoWired(name = KEY_TASK_TYPE)
    var taskType: Int = 0

    @JvmField
    @AutoWired(name = KEY_TASK_CLONE)
    var isClone: Boolean = false

    private lateinit var recyclerConditions: RecyclerView
    private lateinit var recyclerActions: RecyclerView

    private lateinit var conditionsAdapter: TaskSettingAdapter
    private lateinit var actionsAdapter: TaskSettingAdapter

    private var itemListConditions = mutableListOf<TaskSetting>()
    private var itemListActions = mutableListOf<TaskSetting>()

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentTasksEditBinding {
        return FragmentTasksEditBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        titleBar = super.initTitle()!!.setImmersive(false)
        titleBar!!.setTitle(R.string.menu_tasks)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        if (taskId <= 0) { //新增
            titleBar?.setSubTitle(getString(R.string.add_task))
            binding!!.btnDel.setText(R.string.discard)
        } else { //编辑 & 克隆
            binding!!.btnDel.setText(R.string.del)
            initForm()
        }

        recyclerConditions = findViewById(R.id.recycler_conditions)
        recyclerActions = findViewById(R.id.recycler_actions)

        // 初始化 RecyclerView 和 Adapter
        initRecyclerViews()

        // 添加示例项目
        // addSampleItems()

        // 设置拖动排序
        val conditionsCallback = ItemMoveCallback(object : ItemMoveCallback.Listener {
            override fun onItemMove(fromPosition: Int, toPosition: Int) {
                Log.d(TAG, "onItemMove: $fromPosition $toPosition")
                conditionsAdapter.onItemMove(fromPosition, toPosition)
            }

            override fun onDragFinished() {
                //itemListConditions保持与adapter一致
                itemListConditions = conditionsAdapter.itemList.toMutableList()
                Log.d(TAG, "onItemMove: $itemListConditions")
            }
        })

        val itemTouchHelperConditions = ItemTouchHelper(conditionsCallback)
        itemTouchHelperConditions.attachToRecyclerView(recyclerConditions)
        conditionsAdapter.setTouchHelper(itemTouchHelperConditions)

        val actionsCallback = ItemMoveCallback(object : ItemMoveCallback.Listener {
            override fun onItemMove(fromPosition: Int, toPosition: Int) {
                Log.d(TAG, "onItemMove: $fromPosition $toPosition")
                actionsAdapter.onItemMove(fromPosition, toPosition)
            }

            override fun onDragFinished() {
                //itemListActions保持与adapter一致
                itemListActions = actionsAdapter.itemList.toMutableList()
                Log.d(TAG, "onItemMove: $itemListActions")
            }
        })

        val itemTouchHelperActions = ItemTouchHelper(actionsCallback)
        itemTouchHelperActions.attachToRecyclerView(recyclerActions)
        actionsAdapter.setTouchHelper(itemTouchHelperActions)
    }

    override fun initListeners() {
        binding!!.layoutAddCondition.setOnClickListener(this)
        binding!!.layoutAddAction.setOnClickListener(this)
        binding!!.btnTest.setOnClickListener(this)
        binding!!.btnDel.setOnClickListener(this)
        binding!!.btnSave.setOnClickListener(this)
    }

    @SuppressLint("InflateParams")
    @SingleClick
    override fun onClick(v: View) {
        try {
            when (v.id) {
                R.id.layout_add_condition -> {
                    val view: View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_task_condition_bottom_sheet, null)
                    val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView)

                    WidgetUtils.initGridRecyclerView(recyclerView, 4, DensityUtils.dp2px(1f))
                    val widgetItemAdapter = WidgetItemAdapter(TASK_CONDITION_FRAGMENT_LIST)
                    widgetItemAdapter.setOnItemClickListener(that)
                    recyclerView.adapter = widgetItemAdapter

                    dialog.setContentView(view)
                    dialog.setCancelable(true)
                    dialog.setCanceledOnTouchOutside(true)
                    dialog.show()
                    WidgetUtils.transparentBottomSheetDialogBackground(dialog)
                }

                R.id.layout_add_action -> {
                    val view: View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_task_action_bottom_sheet, null)
                    val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView)

                    WidgetUtils.initGridRecyclerView(recyclerView, 4, DensityUtils.dp2px(1f))
                    val widgetItemAdapter = WidgetItemAdapter(TASK_ACTION_FRAGMENT_LIST)
                    widgetItemAdapter.setOnItemClickListener(that)
                    recyclerView.adapter = widgetItemAdapter

                    dialog.setContentView(view)
                    dialog.setCancelable(true)
                    dialog.setCanceledOnTouchOutside(true)
                    dialog.show()
                    WidgetUtils.transparentBottomSheetDialogBackground(dialog)
                }

                R.id.btn_test -> {
                    val taskNew = checkForm()
                    testTask(taskNew)
                    return
                }

                R.id.btn_del -> {
                    if (taskId <= 0 || isClone) {
                        popToBack()
                        return
                    }

                    //TODO: 删除前确认
                    return
                }

                R.id.btn_save -> {
                    val taskNew = checkForm()
                    if (isClone) taskNew.id = 0
                    Log.d(TAG, taskNew.toString())
                    viewModel.insertOrUpdate(taskNew)
                    XToastUtils.success(R.string.tipSaveSuccess)
                    popToBack()
                    return
                }
            }
        } catch (e: Exception) {
            XToastUtils.error(e.message.toString())
            e.printStackTrace()
        }
    }

    //初始化表单
    private fun initForm() {
        AppDatabase.getInstance(requireContext()).taskDao().get(taskId).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(object : SingleObserver<Task> {
            override fun onSubscribe(d: Disposable) {}

            override fun onError(e: Throwable) {
                e.printStackTrace()
            }

            override fun onSuccess(task: Task) {
                Log.d(TAG, task.toString())
                if (isClone) {
                    titleBar?.setSubTitle(getString(R.string.clone_task))
                    binding!!.btnDel.setText(R.string.discard)
                } else {
                    titleBar?.setSubTitle(getString(R.string.edit_task))
                }
                binding!!.etName.setText(task.name)
                binding!!.sbStatus.isChecked = task.status == STATUS_ON
            }
        })
    }

    //提交前检查表单
    private fun checkForm(): Task {
        return Task()
    }

    private fun testTask(task: Task) {
    }

    @SingleClick
    override fun onItemClick(itemView: View, widgetInfo: PageInfo, pos: Int) {
        try {
            dialog.dismiss()
            Log.d(TAG, "onItemClick: $widgetInfo")
            //判断点击的是条件还是动作
            if (widgetInfo.classPath.contains(".condition.")) {
                //判断是否已经添加过该类型条件
                for (item in itemListConditions) {
                    //注意：TASK_CONDITION_XXX 枚举值 等于 TASK_CONDITION_FRAGMENT_LIST 索引加上 KEY_BACK_CODE_CONDITION，不可改变
                    if (item.type == pos + KEY_BACK_CODE_CONDITION) {
                        XToastUtils.error("已经添加过该类型条件")
                        return
                    }
                }
            } else {
                //判断是否已经添加过该类型动作
                for (item in itemListActions) {
                    //注意：TASK_ACTION_XXX 枚举值 等于 TASK_ACTION_FRAGMENT_LIST 索引加上 KEY_BACK_CODE_ACTION，不可改变
                    if (item.type == pos + KEY_BACK_CODE_ACTION) {
                        XToastUtils.error("已经添加过该类型动作")
                        return
                    }
                }
            }
            @Suppress("UNCHECKED_CAST") PageOption.to(Class.forName(widgetInfo.classPath) as Class<XPageFragment>) //跳转的fragment
                .setRequestCode(0) //requestCode: 0 新增 、>0 编辑（itemListXxx 的索引加1）
                .open(this)
        } catch (e: Exception) {
            e.printStackTrace()
            XToastUtils.error(e.message.toString())
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onFragmentResult(requestCode, resultCode, data)
        Log.d(TAG, "requestCode:$requestCode resultCode:$resultCode data:$data")
        if (data != null) {
            val extras = data.extras
            var setting: String? = null
            if (resultCode in KEY_BACK_CODE_CONDITION..KEY_BACK_CODE_CONDITION + 999) {
                setting = extras!!.getString(KEY_BACK_DATA_CONDITION)
                if (setting == null) return
                //注意：TASK_CONDITION_XXX 枚举值 等于 TASK_CONDITION_FRAGMENT_LIST 索引加上 KEY_BACK_CODE_CONDITION，不可改变
                val widgetInfoIndex = resultCode - KEY_BACK_CODE_CONDITION
                if (widgetInfoIndex >= TASK_CONDITION_FRAGMENT_LIST.size) return
                val widgetInfo = TASK_CONDITION_FRAGMENT_LIST[widgetInfoIndex]
                val taskSetting: TaskSetting
                when (resultCode) {
                    TASK_CONDITION_CRON -> {
                        val settingVo = Gson().fromJson(setting, CronSetting::class.java)
                        Log.d(TAG, settingVo.toString())
                        taskSetting = TaskSetting(
                            resultCode, widgetInfo.name, settingVo.description, setting, requestCode
                        )
                    }

                    TASK_CONDITION_BATTERY -> {
                        val settingVo = Gson().fromJson(setting, CronSetting::class.java)
                        Log.d(TAG, settingVo.toString())
                        taskSetting = TaskSetting(
                            resultCode, widgetInfo.name, settingVo.description, setting, requestCode
                        )
                    }

                    else -> {
                        return
                    }
                }
                //requestCode: 等于 itemListConditions 的索引加1
                if (requestCode == 0) {
                    taskSetting.position = itemListConditions.size
                    itemListConditions.add(taskSetting)
                } else {
                    itemListConditions[requestCode - 1] = taskSetting
                }
                conditionsAdapter.notifyDataSetChanged()
            } else if (resultCode == KEY_BACK_CODE_ACTION) {
                setting = extras!!.getString(KEY_BACK_DATA_ACTION)
            }
            Log.d(TAG, "requestCode:$requestCode resultCode:$resultCode setting:$setting")
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun addSampleItems() {
        // 添加示例项目到列表中
        itemListConditions.add(TaskSetting(TYPE_DINGTALK_GROUP_ROBOT, "DingTalk 1", "Description 1"))
        itemListConditions.add(TaskSetting(TYPE_DINGTALK_GROUP_ROBOT, "DingTalk 2", "Description 2"))
        itemListConditions.add(TaskSetting(TYPE_DINGTALK_GROUP_ROBOT, "DingTalk 3", "Description 3"))
        itemListActions.add(TaskSetting(TYPE_EMAIL, "Email 1", "Description 1"))
        itemListActions.add(TaskSetting(TYPE_EMAIL, "Email 2", "Description 2"))
        itemListActions.add(TaskSetting(TYPE_EMAIL, "Email 3", "Description 3"))

        // 更新 Adapter
        conditionsAdapter.notifyDataSetChanged()
        actionsAdapter.notifyDataSetChanged()
    }

    private fun initRecyclerViews() {
        conditionsAdapter = TaskSettingAdapter(itemListConditions, { position -> editCondition(position) }, { position -> removeCondition(position) })

        actionsAdapter = TaskSettingAdapter(itemListActions, { position -> editAction(position) }, { position -> removeAction(position) })

        recyclerConditions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = conditionsAdapter
        }

        recyclerActions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = actionsAdapter
        }
    }

    private fun editCondition(position: Int) {
        // 实现编辑条件项目的逻辑
        // 根据 position 对特定项目进行编辑
        val condition = itemListConditions[position]
        Log.d(TAG, "editCondition: $position, $condition")

        val widgetInfoIndex = condition.type - KEY_BACK_CODE_CONDITION
        //判断是否存在
        if (widgetInfoIndex < 0 || widgetInfoIndex >= TASK_CONDITION_FRAGMENT_LIST.size) return
        val widgetInfo = TASK_CONDITION_FRAGMENT_LIST[condition.type - KEY_BACK_CODE_CONDITION]
        @Suppress("UNCHECKED_CAST") PageOption.to(Class.forName(widgetInfo.classPath) as Class<XPageFragment>) //跳转的fragment
            .setRequestCode(position + 1) //requestCode: 0 新增 、>0 编辑（itemListConditions 的索引加1）
            .putString(KEY_EVENT_DATA_CONDITION, condition.setting)
            .open(this)
    }

    private fun removeCondition(position: Int) {
        itemListConditions.removeAt(position)
        conditionsAdapter.notifyItemRemoved(position)
    }

    private fun editAction(position: Int) {
        // 实现编辑操作项目的逻辑
        // 根据 position 对特定项目进行编辑
        val action = itemListActions[position]
        Log.d(TAG, "editAction: $position, $action")

        val widgetInfoIndex = action.type - KEY_BACK_CODE_ACTION
        //判断是否存在
        if (widgetInfoIndex < 0 || widgetInfoIndex >= TASK_ACTION_FRAGMENT_LIST.size) return
        val widgetInfo = TASK_ACTION_FRAGMENT_LIST[action.type - KEY_BACK_CODE_ACTION]
        @Suppress("UNCHECKED_CAST") PageOption.to(Class.forName(widgetInfo.classPath) as Class<XPageFragment>) //跳转的fragment
            .setRequestCode(position + 1) //requestCode: 0 新增 、>0 编辑（itemListActions 的索引加1）
            .putString(KEY_EVENT_DATA_ACTION, action.setting)
            .open(this)
    }

    private fun removeAction(position: Int) {
        itemListActions.removeAt(position)
        actionsAdapter.notifyItemRemoved(position)
    }
}