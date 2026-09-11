package com.example.novel_summary.ui.customai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.novel_summary.R
import com.example.novel_summary.databinding.ActivityCustomAiApiManagerBinding
import com.example.novel_summary.databinding.ItemCustomAiApiBinding
import com.example.novel_summary.utils.ToastUtils
import com.example.novel_summary.utils.customai.CustomAiApi
import com.example.novel_summary.utils.customai.CustomAiStore
import java.util.UUID

class CustomAiApiManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomAiApiManagerBinding

    private val adapter = CustomAiAdapter(
        onClick = { api ->
            showApiDialog(api)
        },
        onDelete = { api ->
            confirmDeleteApi(api)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCustomAiApiManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()

        refreshApis()
    }

    override fun onResume() {
        super.onResume()
        refreshApis()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Custom AI APIs"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        binding.rvCustomApis.layoutManager = LinearLayoutManager(this)
        binding.rvCustomApis.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnAddCustomApi.setOnClickListener {
            showApiDialog(null)
        }
    }

    private fun refreshApis() {
        val apis = CustomAiStore.getApis(this)

        adapter.submitList(apis)

        binding.tvEmptyCustomApis.isVisible = apis.isEmpty()
        binding.rvCustomApis.isVisible = apis.isNotEmpty()
    }

    private fun showApiDialog(existingApi: CustomAiApi?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_ai_api, null)

        val etName = dialogView.findViewById<EditText>(R.id.etCustomApiName)
        val etBaseUrl = dialogView.findViewById<EditText>(R.id.etCustomApiBaseUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etCustomApiKey)
        val etModel = dialogView.findViewById<EditText>(R.id.etCustomApiModel)
        val etMaxChars = dialogView.findViewById<EditText>(R.id.etCustomApiMaxChars)

        etName.setText(existingApi?.name ?: "")
        etBaseUrl.setText(existingApi?.baseUrl ?: "")
        etApiKey.setText(existingApi?.apiKey ?: "")
        etModel.setText(existingApi?.model ?: "")
        etMaxChars.setText((existingApi?.maxChars ?: 100_000).toString())

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existingApi == null) "Add Custom AI API" else "Edit Custom AI API")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)

        if (existingApi != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                confirmDeleteApi(existingApi)
            }
        }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val apiKey = etApiKey.text.toString().trim()
            val model = etModel.text.toString().trim()
            val maxCharsText = etMaxChars.text.toString().trim()

            val error = CustomAiStore.validate(
                context = this,
                existingApi = existingApi,
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                maxCharsText = maxCharsText
            )

            if (error != null) {
                ToastUtils.showError(this, error)
                return@setOnClickListener
            }

            val api = CustomAiApi(
                id = existingApi?.id ?: UUID.randomUUID().toString(),
                name = name,
                baseUrl = CustomAiStore.normalizeBaseUrl(baseUrl),
                apiKey = apiKey,
                model = model,
                maxChars = maxCharsText.toIntOrNull() ?: 100_000
            )

            CustomAiStore.upsertApi(this, api)

            ToastUtils.showSuccess(this, "Custom AI API saved")

            refreshApis()
            dialog.dismiss()
        }
    }

    private fun confirmDeleteApi(api: CustomAiApi) {
        AlertDialog.Builder(this)
            .setTitle("Delete API")
            .setMessage("Delete \"${api.name}\"?\n\nThis only removes it from this app.")
            .setPositiveButton("Delete") { _, _ ->
                CustomAiStore.deleteApi(this, api.id)
                ToastUtils.showSuccess(this, "Custom AI API deleted")
                refreshApis()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class CustomAiAdapter(
        private val onClick: (CustomAiApi) -> Unit,
        private val onDelete: (CustomAiApi) -> Unit
    ) : RecyclerView.Adapter<CustomAiAdapter.CustomAiViewHolder>() {

        private val items = mutableListOf<CustomAiApi>()

        fun submitList(newItems: List<CustomAiApi>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomAiViewHolder {
            val binding = ItemCustomAiApiBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return CustomAiViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: CustomAiViewHolder, position: Int) {
            val api = items[position]

            holder.binding.tvCustomApiName.text = api.name
            holder.binding.tvCustomApiDetails.text = "${api.model}\n${api.baseUrl}"

            holder.binding.root.setOnClickListener {
                onClick(api)
            }

            holder.binding.btnDeleteCustomApi.setOnClickListener {
                onDelete(api)
            }
        }

        class CustomAiViewHolder(
            val binding: ItemCustomAiApiBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }
}