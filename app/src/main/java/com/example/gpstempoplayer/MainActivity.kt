package com.example.gpstempoplayer

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max

class MainActivity : AppCompatActivity() {
 private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
 private var minRate=.75f; private var maxRate=1.75f; private var minSpeed=10f; private var maxSpeed=100f
 private var smooth=3; private var sensitivity=.55f; private var filtered=0f
 private var controller: MediaController?=null; private var future: ListenableFuture<MediaController>?=null
 private lateinit var location: LocationManager
 private lateinit var speedLabel: TextView; private lateinit var rateLabel: TextView; private lateinit var status: TextView
 private lateinit var songLabel: TextView; private lateinit var play: Button
 private val uris= mutableListOf<Uri>(); private val names= mutableListOf<String>()
 private var listener: LocationListener?=null
 private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
  list?.forEach { u -> try { contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
   if (!uris.contains(u)) { uris.add(u); names.add(nameOf(u)) } }
  prefs.edit().putString("uris",uris.joinToString("\n")).apply()
  if (controller?.mediaItemCount==0 && uris.isNotEmpty()) playIndex(0)
 }
 private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { yes -> yes }) startGps() }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); loadSettings()
  prefs.getString("uris","")?.lines()?.filter { it.isNotBlank() }?.forEach { try { val u=Uri.parse(it); uris.add(u); names.add(nameOf(u)) } catch (_:Exception) {} }
  location=getSystemService(LOCATION_SERVICE) as LocationManager
  window.statusBarColor=0xff070916.toInt(); window.navigationBarColor=0xff070916.toInt()
  val root=LinearLayout(this).apply { orientation=1; gravity=Gravity.CENTER_HORIZONTAL; setPadding(28,32,28,24); setBackgroundColor(0xff070916.toInt()) }
  fun text(s:String,size:Float,color:Int=0xfff6f3ff.toInt())=TextView(this).apply { text=s;textSize=size;setTextColor(color);gravity=Gravity.CENTER;setPadding(4,10,4,10) }
  root.addView(text("♫ GPS TEMPO",24f,0xffe448ff.toInt()))
  speedLabel=text("— km/h",46f);root.addView(speedLabel)
  rateLabel=text("1.00×",28f,0xffe448ff.toInt());root.addView(rateLabel)
  status=text("در انتظار GPS",13f,0xffaaa6ca.toInt());root.addView(status)
  songLabel=text("فایلی انتخاب نشده",17f);root.addView(songLabel)
  play=Button(this).apply { text="▶ پخش / مکث"; setOnClickListener { val p=controller; if(p==null) status.text="سرویس پخش در حال آماده‌سازی است" else if(p.mediaItemCount==0) picker.launch(arrayOf("audio/*")) else if(p.isPlaying)p.pause() else p.play() } };root.addView(play)
  root.addView(Button(this).apply { text="＋ افزودن موسیقی";setOnClickListener { picker.launch(arrayOf("audio/*")) } })
  root.addView(Button(this).apply { text="☷ لیست پخش";setOnClickListener { showPlaylist() } })
  root.addView(Button(this).apply { text="⚙ تنظیمات";setOnClickListener { settings() } })
  setContentView(root)
  val token=SessionToken(this,ComponentName(this,PlaybackService::class.java))
  future=MediaController.Builder(this,token).buildAsync()
  future!!.addListener({ try { controller=future!!.get(); controller?.addListener(object:Player.Listener {
   override fun onIsPlayingChanged(isPlaying:Boolean){play.text=if(isPlaying)"Ⅱ مکث" else "▶ پخش / مکث"}
   override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int){ songLabel.text=names.getOrNull(controller?.currentMediaItemIndex ?: 0) ?: "" }
  }); restore() } catch (_:Exception){status.text="اتصال سرویس پخش ناموفق بود"} },mainExecutor)
  val fine=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
  val coarse=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
  if(fine||coarse) startGps() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
 }
 private fun loadSettings(){minRate=prefs.getFloat("minRate",.75f);maxRate=prefs.getFloat("maxRate",1.75f);minSpeed=prefs.getFloat("minSpeed",10f);maxSpeed=prefs.getFloat("maxSpeed",100f);smooth=prefs.getInt("smooth",3);sensitivity=prefs.getFloat("sensitivity",.55f)}
 private fun saveSettings(){prefs.edit().putFloat("minRate",minRate).putFloat("maxRate",maxRate).putFloat("minSpeed",minSpeed).putFloat("maxSpeed",maxSpeed).putInt("smooth",smooth).putFloat("sensitivity",sensitivity).apply()}
 private fun settings(){
  val box=LinearLayout(this).apply{orientation=1;setPadding(28,8,28,0)}
  fun field(label:String,value:Float):EditText { box.addView(TextView(this).apply{text=label;setTextColor(0xfff6f3ff.toInt())}); return EditText(this).apply { setSingleLine();inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(value.toString());setTextColor(0xfff6f3ff.toInt());box.addView(this) } }
  val a=field("حداقل سرعت پخش (مثلاً 0.75×)",minRate);val b=field("حداکثر سرعت پخش (مثلاً 1.75×)",maxRate)
  val c=field("حداقل سرعت حرکت km/h",minSpeed);val d=field("حداکثر سرعت حرکت km/h",maxSpeed)
  val e=field("زمان نرم‌شدن (ثانیه، 0 تا 10)",smooth.toFloat());val f=field("حساسیت GPS (0.05 تا 1)",sensitivity)
  AlertDialog.Builder(this).setTitle("تنظیمات پیشرفته").setView(ScrollView(this).apply{addView(box)})
   .setPositiveButton("ذخیره"){_,_->try{
    val aa=a.text.toString().toFloat();val bb=b.text.toString().toFloat();val cc=c.text.toString().toFloat();val dd=d.text.toString().toFloat();val ee=e.text.toString().toFloat().toInt();val ff=f.text.toString().toFloat()
    require(aa in .25f..3f && bb in .25f..3f && aa<=bb && cc>=0 && dd>cc && dd<=400 && ee in 0..10 && ff in .05f..1f)
    minRate=aa;maxRate=bb;minSpeed=cc;maxSpeed=dd;smooth=ee;sensitivity=ff;saveSettings();applyRate();status.text="تنظیمات ذخیره شد"
   }catch(_:Exception){status.text="مقادیر نامعتبر است"}}.setNegativeButton("انصراف",null).show()
 }
 private fun startGps(){try{
  listener?.let{location.removeUpdates(it)}
  val provider=if(location.isProviderEnabled(LocationManager.GPS_PROVIDER))LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
  val l=object:LocationListener{override fun onLocationChanged(loc:Location){
   val raw=if(loc.hasSpeed())loc.speed*3.6f else 0f
   val alpha=(sensitivity/(smooth+1f)).coerceIn(.08f,1f)
   filtered=if(filtered==0f)raw else filtered+alpha*(raw-filtered)
   speedLabel.text="%.0f km/h".format(filtered);status.text="GPS فعال";applyRate()
  }}
  listener=l;location.requestLocationUpdates(provider,1000L,0f,l,Looper.getMainLooper())
 }catch(_:Exception){status.text="GPS در دسترس نیست"}}
 private fun applyRate(){val t=((filtered-minSpeed)/(maxSpeed-minSpeed).coerceAtLeast(1f)).coerceIn(0f,1f);val r=if(filtered<minSpeed)minRate else minRate+(maxRate-minRate)*t;rateLabel.text="%.2f×".format(r);try{controller?.playbackParameters=PlaybackParameters(r)}catch(_:Exception){}}
 private fun playIndex(i:Int){val p=controller?:return;if(uris.isEmpty())return;val idx=i.coerceIn(0,uris.lastIndex);p.setMediaItems(uris.map{MediaItem.fromUri(it)},idx,0);p.prepare();p.play();prefs.edit().putInt("index",idx).putLong("position",0).putBoolean("playing",true).apply()}
 private fun restore(){val p=controller?:return;if(p.mediaItemCount==0&&uris.isNotEmpty()){p.setMediaItems(uris.map{MediaItem.fromUri(it)},prefs.getInt("index",0).coerceIn(0,uris.lastIndex),prefs.getLong("position",0));p.prepare();if(prefs.getBoolean("playing",false))p.play()};songLabel.text=names.getOrNull(p.currentMediaItemIndex)?: "فایلی انتخاب نشده"}
 private fun showPlaylist(){val list=ListView(this);list.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_list_item_1,names);list.setOnItemClickListener{_,_,i,_->playIndex(i)};AlertDialog.Builder(this).setTitle("لیست پخش").setView(list).setPositiveButton("افزودن"){_,_->picker.launch(arrayOf("audio/*"))}.setNegativeButton("بستن",null).show()}
 private fun nameOf(uri:Uri):String{var n=uri.lastPathSegment?:"Audio";try{contentResolver.query(uri,null,null,null,null)?.use{c->val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&c.moveToFirst())n=c.getString(i)?:n}}catch(_:Exception){};return n.substringAfterLast('/')}
 override fun onStop(){controller?.let{prefs.edit().putInt("index",it.currentMediaItemIndex.coerceAtLeast(0)).putLong("position",it.currentPosition).putBoolean("playing",it.isPlaying).apply()};super.onStop()}
 override fun onDestroy(){listener?.let{try{location.removeUpdates(it)}catch(_:Exception){}};future?.let{MediaController.releaseFuture(it)};super.onDestroy()}
}
