package com.example.tareamov.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.ui.model.MCPTool

/**
 * Adapter for displaying MCP tools in a RecyclerView
 */
class MCPToolsAdapter(
    private val tools: List<MCPTool>,
    private val onToolExecute: (MCPTool) -> Unit
) : RecyclerView.Adapter<MCPToolsAdapter.ToolViewHolder>() {

    class ToolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val toolIcon: ImageView = view.findViewById(R.id.toolIcon)
        val toolName: TextView = view.findViewById(R.id.toolName)
        val toolDescription: TextView = view.findViewById(R.id.toolDescription)
        val btnExecuteTool: ImageButton = view.findViewById(R.id.btnExecuteTool)
        val parametersLayout: LinearLayout = view.findViewById(R.id.parametersLayout)
        val paramsContainer: LinearLayout = view.findViewById(R.id.paramsContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mcp_tool, parent, false)
        return ToolViewHolder(view)
    }

    override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
        val tool = tools[position]
        
        holder.toolName.text = tool.getDisplayName()
        holder.toolDescription.text = tool.description
        
        // Show/hide parameters
        val params = tool.getParameters()
        if (params.isNotEmpty()) {
            holder.parametersLayout.visibility = View.VISIBLE
            holder.paramsContainer.removeAllViews()
            
            params.forEach { (name, param) ->
                val paramView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_tool_parameter, holder.paramsContainer, false)
                
                val paramName = paramView.findViewById<TextView>(R.id.paramName)
                val paramDesc = paramView.findViewById<TextView>(R.id.paramDescription)
                val requiredBadge = paramView.findViewById<TextView>(R.id.requiredBadge)
                
                paramName.text = name
                paramDesc.text = param.description
                requiredBadge.visibility = if (param.required) View.VISIBLE else View.GONE
                
                holder.paramsContainer.addView(paramView)
            }
        } else {
            holder.parametersLayout.visibility = View.GONE
        }
        
        // Execute button
        holder.btnExecuteTool.setOnClickListener {
            onToolExecute(tool)
        }
        
        // Toggle parameters visibility on click
        holder.itemView.setOnClickListener {
            holder.parametersLayout.visibility = if (holder.parametersLayout.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    override fun getItemCount() = tools.size
}
