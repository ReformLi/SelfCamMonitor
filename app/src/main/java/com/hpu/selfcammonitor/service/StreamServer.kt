package com.hpu.selfcammonitor.service

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.util.Base64
import com.hpu.selfcammonitor.utils.MJPEGStreamer

class StreamServer(port: Int = 8080) : NanoHTTPD(port) {

    private lateinit var mjpegStreamer: MJPEGStreamer

    var isMjpegEnabled: Boolean = true   // 新增状态

    var username: String? = null
    var password: String? = null

    fun setMJPEGStreamer(streamer: MJPEGStreamer) {
        this.mjpegStreamer = streamer
    }

    override fun serve(session: IHTTPSession?): Response {
        // 增加 username 和 password 属性验证
        // 认证检查
        if (username != null && password != null) {
            val auth = session?.headers?.get("authorization")
            if (auth == null || !auth.startsWith("Basic ")) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "需要认证"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
            val cred = String(
                Base64.decode(auth.substring(6), Base64.DEFAULT)
            ).split(":")
            if (cred.size != 2 || cred[0] != username || cred[1] != password) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "认证失败"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
        }

        if (session?.uri == "/" || session?.uri == "/index.html") {
            val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", VIEWER_HTML)
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        if (session?.uri == "/status") {
            val json = """{"mjpegEnabled":$isMjpegEnabled,"clientCount":${mjpegStreamer.getClientCount()},"lastFrameAge":${mjpegStreamer.getLastFrameAge()}}"""
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        if (session?.uri == "/snapshot") {
            if (!isMjpegEnabled) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, "text/plain", "推流已关闭"
                )
            }
            val jpeg = mjpegStreamer.getLatestJpeg()
            if (jpeg == null) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, "text/plain", "暂无画面"
                )
            }
            val res = newFixedLengthResponse(
                Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong()
            )
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        if (session?.uri == "/video") {
            // 检查推流开关
            if (!isMjpegEnabled) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "MJPEG 推流已关闭，请在 App 中开启。"
                )
            }

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut)

            mjpegStreamer.addClient(pipedOut)

            return newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=${MJPEGStreamer.Companion.BOUNDARY}",
                pipedIn
            )
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }

    companion object {
        private val VIEWER_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<title>SelfCamMonitor</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{height:100%;background:#1a1a1a;color:#e0e0e0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;overflow:hidden}
body{display:flex;flex-direction:column}
#topbar{display:flex;align-items:center;padding:8px 12px;background:#2a2a2a;gap:8px;flex-shrink:0}
#dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
#dot.connecting{background:#ffc107;animation:pulse 1s infinite}
#dot.live{background:#28a745}
#dot.disconnected{background:#dc3545;animation:pulse 1s infinite}
#dot.off{background:#6c757d}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
#st{font-size:14px;flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#fps{font-size:12px;color:#888;flex-shrink:0;min-width:40px;text-align:right}
.btn{background:#3a3a3a;color:#e0e0e0;border:1px solid #555;border-radius:6px;padding:6px 12px;font-size:13px;cursor:pointer;flex-shrink:0;touch-action:manipulation;white-space:nowrap}
.btn:active{background:#4a4a4a}
#container{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;position:relative;min-height:0;touch-action:none}
#wrapper{position:relative;transform-origin:center center;transition:none;display:flex;align-items:center;justify-content:center;will-change:transform}
#img{display:block;max-width:100%;max-height:100%;object-fit:contain;-webkit-user-drag:none;user-select:none}
#overlay{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;color:#666;font-size:16px;display:none}
#overlay.show{display:block}
#bottombar{padding:6px 12px;background:#2a2a2a;font-size:12px;color:#888;flex-shrink:0;display:flex;justify-content:space-between}
#fs-ctrl{display:none;position:fixed;top:10px;right:10px;gap:8px;z-index:100}
:fullscreen #topbar,:fullscreen #bottombar{display:none}
:fullscreen #container{height:100vh}
:fullscreen #fs-ctrl{display:flex}
:-webkit-full-screen #topbar,:-webkit-full-screen #bottombar{display:none}
:-webkit-full-screen #container{height:100vh}
:-webkit-full-screen #fs-ctrl{display:flex}
</style>
</head>
<body>
<div id="topbar">
<div id="dot" class="connecting"></div>
<span id="st">连接中...</span>
<span id="fps"></span>
<button id="btn-r" class="btn">旋转</button>
<button id="btn-f" class="btn">全屏</button>
</div>
<div id="container">
<div id="wrapper"><img id="img" alt="监控画面"></div>
<div id="overlay"></div>
</div>
<div id="bottombar">
<span id="ic"></span>
<span id="it"></span>
</div>
<div id="fs-ctrl">
<button class="btn" onclick="doRotate()">旋转</button>
<button class="btn" onclick="toggleFs()">退出全屏</button>
</div>
<script>
var img=document.getElementById('img'),wrapper=document.getElementById('wrapper'),
dot=document.getElementById('dot'),st=document.getElementById('st'),
fps=document.getElementById('fps'),ov=document.getElementById('overlay'),
ic=document.getElementById('ic'),it=document.getElementById('it'),
container=document.getElementById('container');
var HI=3000,ST=5000;
var rot=0,zoom=1,panX=0,panY=0,fc=0,lf=0,stat='connecting',bu=null;
var streamReader=null,streamActive=false,streamGen=0,reconnectTimer=null;
function setStatus(s){
  stat=s;dot.className=s;
  var t={connecting:'连接中...',live:'已连接',disconnected:'已断开，正在重连...',off:'推流已关闭'};
  st.textContent=t[s]||s;
  if(s==='disconnected'||s==='off'){ov.textContent=t[s];ov.classList.add('show');}
  else ov.classList.remove('show');
}
function displayFrame(d){
  if(d.length<100)return;
  if(bu)URL.revokeObjectURL(bu);
  bu=URL.createObjectURL(new Blob([d],{type:'image/jpeg'}));
  img.src=bu;lf=Date.now();fc++;
  if(stat!=='live')setStatus('live');
}
function findM(a,m,f){for(var i=f;i<a.length-1;i++)if(a[i]===0xFF&&a[i+1]===m)return i;return -1;}
async function startStream(){
  if(streamActive)return;streamActive=true;
  var gen=++streamGen;
  try{
    var r=await fetch('/video',{cache:'no-store'});
    if(!r.ok||gen!==streamGen){if(gen===streamGen){streamActive=false;setStatus('disconnected');scheduleReconnect();}return;}
    streamReader=r.body.getReader();
    var buf=new Uint8Array(0);
    while(true){
      var rd=await streamReader.read();
      if(gen!==streamGen)break;
      if(rd.done)break;
      var c=rd.value,nb=new Uint8Array(buf.length+c.length);
      nb.set(buf);nb.set(c,buf.length);buf=nb;
      for(;;){
        var soi=findM(buf,0xD8,0);
        if(soi<0){buf=new Uint8Array(0);break;}
        var eoi=findM(buf,0xD9,soi+2);
        if(eoi<0){if(soi>0)buf=buf.slice(soi);break;}
        displayFrame(buf.slice(soi,eoi+2));
        buf=buf.slice(eoi+2);
      }
      if(buf.length>500000)buf=buf.slice(-100000);
    }
  }catch(e){}
  if(gen===streamGen){
    streamActive=false;streamReader=null;
    if(stat!=='off'){setStatus('disconnected');scheduleReconnect();}
  }
}
function stopStream(){
  streamGen++;streamActive=false;
  if(streamReader){try{streamReader.cancel();}catch(e){}streamReader=null;}
  if(reconnectTimer){clearTimeout(reconnectTimer);reconnectTimer=null;}
}
function scheduleReconnect(){
  if(reconnectTimer)clearTimeout(reconnectTimer);
  reconnectTimer=setTimeout(function(){reconnectTimer=null;if(stat!=='off'&&!streamActive){setStatus('connecting');startStream();}},2000);
}
function heartbeat(){
  fetch('/status',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
    if(!d.mjpegEnabled){setStatus('off');stopStream();return;}
    ic.textContent='客户端：'+d.clientCount;
    it.textContent=new Date().toLocaleTimeString();
    if(stat==='off'){setStatus('connecting');startStream();}
  }).catch(function(){
    if(stat!=='off'&&stat!=='disconnected')setStatus('disconnected');
  });
}
setInterval(function(){
  var z=zoom>1.01?(Math.round(zoom*10)/10)+'x':'';
  if(stat==='live'&&fc>0)fps.textContent=z?z+' · '+fc+' fps':fc+' fps';else fps.textContent=z;
  fc=0;
},1000);
setInterval(function(){
  if(stat==='live'&&Date.now()-lf>ST){stopStream();setStatus('connecting');startStream();}
},2000);
function applyTransform(){
  wrapper.style.transform='translate('+panX+'px,'+panY+'px) rotate('+rot+'deg) scale('+zoom+')';
  if(rot===90||rot===270){
    img.style.maxWidth=container.clientHeight+'px';
    img.style.maxHeight=container.clientWidth+'px';
  }else{img.style.maxWidth='100%';img.style.maxHeight='100%';}
}
function doRotate(){
  wrapper.style.transition='transform .3s ease';
  rot=(rot+90)%360;zoom=1;panX=0;panY=0;applyTransform();
  setTimeout(function(){wrapper.style.transition='none';},350);
}
var MIN_Z=1,MAX_Z=5;
function clampZ(v){return Math.max(MIN_Z,Math.min(MAX_Z,v));}
function getDist(t){var dx=t[0].clientX-t[1].clientX,dy=t[0].clientY-t[1].clientY;return Math.sqrt(dx*dx+dy*dy);}
var touching=false,tx=0,ty=0,pinching=false,pd=0,pz=1,lastTap=0;
container.addEventListener('touchstart',function(e){
  if(e.touches.length===2){pinching=true;touching=false;pd=getDist(e.touches);pz=zoom;e.preventDefault();}
  else if(e.touches.length===1&&zoom>1){touching=true;tx=e.touches[0].clientX;ty=e.touches[0].clientY;}
},{passive:false});
container.addEventListener('touchmove',function(e){
  if(pinching&&e.touches.length===2){e.preventDefault();zoom=clampZ(pz*getDist(e.touches)/pd);if(zoom<=1.01){zoom=1;panX=0;panY=0;}applyTransform();}
  else if(touching&&e.touches.length===1){e.preventDefault();panX+=e.touches[0].clientX-tx;panY+=e.touches[0].clientY-ty;tx=e.touches[0].clientX;ty=e.touches[0].clientY;applyTransform();}
},{passive:false});
container.addEventListener('touchend',function(e){
  if(e.touches.length<2)pinching=false;
  if(e.touches.length===1&&zoom>1){touching=true;tx=e.touches[0].clientX;ty=e.touches[0].clientY;}
  else if(e.touches.length===0){touching=false;var now=Date.now();if(now-lastTap<300){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();}lastTap=now;}
});
container.addEventListener('touchcancel',function(){pinching=false;touching=false;});
container.addEventListener('wheel',function(e){e.preventDefault();zoom=clampZ(zoom*(e.deltaY<0?1.1:0.9));if(zoom<=1.01){zoom=1;panX=0;panY=0;}applyTransform();},{passive:false});
var mousing=false,mx=0,my=0;
container.addEventListener('mousedown',function(e){if(zoom>1){mousing=true;mx=e.clientX;my=e.clientY;e.preventDefault();}});
window.addEventListener('mousemove',function(e){if(mousing){panX+=e.clientX-mx;panY+=e.clientY-my;mx=e.clientX;my=e.clientY;applyTransform();}});
window.addEventListener('mouseup',function(){mousing=false;});
container.addEventListener('dblclick',function(){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();});
function toggleFs(){
  var e=document.documentElement,fs=document.fullscreenElement||document.webkitFullscreenElement;
  if(!fs){if(e.requestFullscreen)e.requestFullscreen();else if(e.webkitRequestFullscreen)e.webkitRequestFullscreen();}
  else{if(document.exitFullscreen)document.exitFullscreen();else if(document.webkitExitFullscreen)document.webkitExitFullscreen();}
}
document.getElementById('btn-r').addEventListener('click',doRotate);
document.getElementById('btn-f').addEventListener('click',toggleFs);
window.addEventListener('resize',applyTransform);
document.addEventListener('fullscreenchange',applyTransform);
document.addEventListener('webkitfullscreenchange',applyTransform);
setStatus('connecting');startStream();
setInterval(heartbeat,HI);heartbeat();
</script>
</body>
</html>
        """.trimIndent()
    }
}