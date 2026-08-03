package com.batterycast.quant.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.batterycast.quant.model.CalendarTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CalendarTargetSource @Inject constructor(@ApplicationContext private val context:Context){
    fun upcoming(limit:Int=10):List<CalendarTarget>{
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        val now=System.currentTimeMillis();val end=now+14*24*60*60_000L
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{appendPath(now.toString());appendPath(end.toString())}.build()
        return runCatching{context.contentResolver.query(uri,arrayOf(CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END),null,null,"${CalendarContract.Instances.BEGIN} ASC")?.use{c->
            val out=mutableListOf<CalendarTarget>();while(c.moveToNext()&&out.size<limit)out+=CalendarTarget(c.getLong(0),c.getString(1)?:"Calendar event",c.getLong(2),c.getLong(3));out
        }.orEmpty()}.getOrDefault(emptyList())
    }
}
