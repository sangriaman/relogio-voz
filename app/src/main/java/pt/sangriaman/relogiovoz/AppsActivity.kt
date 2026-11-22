package pt.sangriaman.relogiovoz

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Lista das apps instaladas (o Tricky é o ecrã inicial, por isso precisa disto). */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = packageManager
        val apps: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = 150
            verticalSpacing = 12
            setBackgroundColor(Color.rgb(48, 196, 190))
            setPadding(12, 12, 12, 12)
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(position: Int) = apps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val app = apps[position]
                    return LinearLayout(this@AppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(4, 8, 4, 8)
                        addView(ImageView(this@AppsActivity).apply {
                            setImageDrawable(app.loadIcon(pm))
                        }, LinearLayout.LayoutParams(72, 72))
                        addView(TextView(this@AppsActivity).apply {
                            text = app.loadLabel(pm)
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            gravity = Gravity.CENTER
                            maxLines = 2
                        })
                    }
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                val a = apps[position].activityInfo
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(a.packageName, a.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
        }
        setContentView(grid)
    }
}
