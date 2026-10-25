package com.example.dateorganizer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.location.Geocoder;
import android.location.Address;
import android.media.MediaMetadataRetriever;
import android.view.*;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.*;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.text.SimpleDateFormat;
import org.json.JSONObject;

public class MainActivity extends Activity {
 private static final String LOCAL="com.android.externalstorage.documents";
 private Uri src,dst;
 private TextView status,sourcePath,targetPath,pageText,phaseText,percentText,countText,elapsedText,emptyText;
 private LinearLayout rows;
 private ProgressBar progress;
 private Button move,scanButton,stopButton,previous,next;
 private long startedAt;
 private int alreadySorted;
 private final Handler clock=new Handler(Looper.getMainLooper());
 private final Runnable tick=new Runnable(){public void run(){if(busy){long seconds=(SystemClock.elapsedRealtime()-startedAt)/1000;elapsedText.setText(String.format(Locale.KOREA,"경과 %02d:%02d",seconds/60,seconds%60));clock.postDelayed(this,1000);}}};
 private CheckBox photos,videos,docs;
 private final ArrayList<View> controls=new ArrayList<>();
 private final ArrayList<Item> items=new ArrayList<>();
 private final ArrayList<String> warnings=new ArrayList<>();
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile boolean stopped=false,busy=false;
 private boolean ready=false;
 private int page=0;
 private long lastPaint=0;
 private ImageLabeler labeler;
 private final int ink=Color.rgb(22,36,58),teal=Color.rgb(0,116,104),muted=Color.rgb(100,116,139),line=Color.rgb(224,231,239);
 private static class Item {
  Uri uri;String parentId,name,mime,date,reason,feature="",featureReason="";int type;long size,modified;
  String folder(){return date+"-"+OrganizerRules.safeFeature(feature);}
 }
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  getWindow().setStatusBarColor(ink);getWindow().setNavigationBarColor(ink);
  LinearLayout base=new LinearLayout(this);base.setOrientation(1);base.setBackgroundColor(Color.rgb(243,246,250));
  base.setPadding(dp(18),dp(8),dp(18),dp(10));
  if(Build.VERSION.SDK_INT>=35)base.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(18)+in.getSystemWindowInsetLeft(),dp(8)+in.getSystemWindowInsetTop(),dp(18)+in.getSystemWindowInsetRight(),dp(10)+in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout titleBlock=new LinearLayout(this);titleBlock.setOrientation(1);
  TextView title=text("파일 정리",26);title.setTypeface(null,Typeface.BOLD);titleBlock.addView(title);
  TextView subtitle=text("날짜와 사진 특징으로 정돈하는 내 파일",12);subtitle.setTextColor(muted);titleBlock.addView(subtitle);
  header.addView(titleBlock,new LinearLayout.LayoutParams(0,-2,1));
  Button exit=button("×",()->{if(busy){stopped=true;show("작업을 안전하게 중지하고 있습니다.");}else finish();});exit.setTextSize(26);exit.setContentDescription("닫기");header.addView(exit,new LinearLayout.LayoutParams(dp(48),dp(48)));base.addView(header);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);LinearLayout.LayoutParams scrollLp=new LinearLayout.LayoutParams(-1,0,1);scrollLp.topMargin=dp(14);base.addView(scroll,scrollLp);
  LinearLayout location=card(content);section(location,"01  폴더 설정","같은 폴더 안에서 정리하거나 다른 폴더로 이동합니다.");
  sourcePath=folderChoice(location,"원본 폴더",()->pick(1));
  targetPath=folderChoice(location,"저장 폴더",()->pick(2));
  Button same=button("원본 폴더 안에 정리",()->{if(src==null){show("원본 폴더를 먼저 선택하세요.");return;}dst=src;invalidatePlan();updatePaths();show("원본 폴더 안에 날짜별 하위 폴더를 만듭니다.");});
  same.setTextSize(13);location.addView(same,new LinearLayout.LayoutParams(-1,dp(44)));controls.add(same);
  LinearLayout kinds=card(content);section(kinds,"02  정리할 파일","사진의 특징은 기기에서 분석하며 직접 수정할 수 있습니다.");
  LinearLayout checks=new LinearLayout(this);photos=check("사진",checks);videos=check("동영상",checks);docs=check("문서",checks);kinds.addView(checks);
  TextView example=text("폴더 예시   261025-바다  ·  261025-문서",12);example.setTextColor(teal);example.setPadding(0,dp(8),0,dp(8));kinds.addView(example);
  scanButton=addControl(kinds,"검색 · 분류 미리보기",this::askScan);
  LinearLayout preview=card(content);section(preview,"분류 미리보기","촬영일 → 파일명 날짜 → 수정일. 저장 위치의 날짜 폴더는 재정리하지 않습니다.");
  emptyText=text("검색하면 파일별 저장 위치를 확인할 수 있습니다.",13);emptyText.setTextColor(muted);emptyText.setPadding(0,dp(16),0,dp(16));preview.addView(emptyText);
  rows=new LinearLayout(this);rows.setOrientation(1);preview.addView(rows);
  LinearLayout pages=new LinearLayout(this);previous=button("이전",()->{if(page>0){page--;render();}});next=button("다음",()->{page++;render();});pageText=text("0 / 0",13);pageText.setGravity(Gravity.CENTER);
  pages.addView(previous,new LinearLayout.LayoutParams(0,dp(44),1));pages.addView(pageText,new LinearLayout.LayoutParams(0,dp(44),1));pages.addView(next,new LinearLayout.LayoutParams(0,dp(44),1));preview.addView(pages);
  LinearLayout progressCard=card(base);progressCard.setPadding(dp(14),dp(10),dp(14),dp(10));
  LinearLayout progressHead=new LinearLayout(this);progressHead.setGravity(Gravity.CENTER_VERTICAL);phaseText=text("준비",15);phaseText.setTypeface(null,Typeface.BOLD);progressHead.addView(phaseText,new LinearLayout.LayoutParams(0,-2,1));percentText=text("—",24);percentText.setTypeface(null,Typeface.BOLD);percentText.setTextColor(teal);progressHead.addView(percentText);progressCard.addView(progressHead);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(teal));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(line));progressCard.addView(progress,new LinearLayout.LayoutParams(-1,dp(9)));
  LinearLayout metrics=new LinearLayout(this);countText=text("처리 0 / 0개",12);countText.setTextColor(muted);metrics.addView(countText,new LinearLayout.LayoutParams(0,-2,1));elapsedText=text("경과 00:00",12);elapsedText.setTextColor(muted);metrics.addView(elapsedText);progressCard.addView(metrics);
  status=text("폴더를 선택한 뒤 검색을 시작하세요.",12);status.setTextColor(muted);status.setMaxLines(3);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(0,dp(5),0,0);status.setOnClickListener(v->{if(!busy)new AlertDialog.Builder(this).setTitle("작업 상세").setMessage(status.getText()).setPositiveButton("닫기",null).show();});progressCard.addView(status);
  LinearLayout actions=new LinearLayout(this);stopButton=button("중지",()->{stopped=true;stopButton.setEnabled(false);phaseText.setText("중지 중");show("원본을 보존하며 안전하게 중지하고 있습니다.");});actions.addView(stopButton,new LinearLayout.LayoutParams(dp(86),dp(54)));
  move=button("파일 이동 실행",this::askMove);move.setTextColor(Color.WHITE);move.setBackground(tint(teal));LinearLayout.LayoutParams moveLp=new LinearLayout.LayoutParams(0,dp(54),1);moveLp.leftMargin=dp(10);actions.addView(move,moveLp);base.addView(actions);
  setContentView(base);setBusy(false);render();
  String pending=getPreferences(0).getString("pending","");
  if(!pending.isEmpty())show("이전 작업이 중단되었습니다. 원본/대상 파일을 확인한 뒤 다시 검색하세요.\n"+pending+"\n검증 전 파일의 원본은 삭제하지 않습니다.");
 }
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(ink);return t;}
 private GradientDrawable tint(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(12));return d;}
 private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(ink);b.setTypeface(null,Typeface.BOLD);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22007468),tint(Color.rgb(232,239,244)),null));b.setOnClickListener(v->r.run());return b;}
 private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(16),dp(16),dp(16),dp(16));c.setBackground(tint(Color.WHITE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(c,lp);return c;}
 private void section(LinearLayout parent,String title,String hint){TextView t=text(title,15);t.setTypeface(null,Typeface.BOLD);parent.addView(t);TextView h=text(hint,12);h.setTextColor(muted);h.setPadding(0,dp(6),0,dp(10));parent.addView(h);}
 private TextView folderChoice(LinearLayout parent,String label,Runnable action){LinearLayout row=new LinearLayout(this);row.setOrientation(1);row.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable bg=tint(Color.rgb(248,250,252));bg.setStroke(dp(1),line);row.setBackground(bg);TextView title=text(label+"  ›",13);title.setTypeface(null,Typeface.BOLD);row.addView(title);TextView path=text("폴더를 선택하세요",12);path.setTextColor(muted);path.setMaxLines(2);path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);row.addView(path);row.setOnClickListener(v->action.run());row.setFocusable(true);row.setContentDescription(label+" 선택");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);parent.addView(row,lp);controls.add(row);return path;}
 private void updatePaths(){sourcePath.setText(src==null?"폴더를 선택하세요":displayPath(src));targetPath.setText(dst==null?"폴더를 선택하세요":displayPath(dst)+(src!=null&&id(root(src)).equals(id(root(dst)))?"  ·  같은 폴더":""));}
 private String displayPath(Uri tree){return DocumentsContract.getTreeDocumentId(tree).replace("primary:","내장 저장소 / ");}
 private void paintProgress(String phase,int completed,int total,double fraction,String detail){runOnUiThread(()->{if(isDestroyed())return;phaseText.setText(stopped?"중지 중":phase);if(total>0){progress.setIndeterminate(false);double ratio=Math.max(0,Math.min(1,(completed+Math.min(.99,fraction))/total));progress.setProgress((int)(ratio*1000));percentText.setText(String.format(Locale.KOREA,"%.1f%%",ratio*100));countText.setText("처리 "+completed+" / "+total+"개");}status.setText(detail);});}
 private String bytes(long n){return n<1048576?String.format(Locale.KOREA,"%.1f KB",n/1024.0):String.format(Locale.KOREA,"%.1f MB",n/1048576.0);}

 private Button addControl(LinearLayout l,String s,Runnable r){Button b=button(s,r);l.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));controls.add(b);return b;}
 private CheckBox check(String name,LinearLayout l){CheckBox c=new CheckBox(this);c.setText(name);c.setChecked(true);c.setTextColor(ink);l.addView(c,new LinearLayout.LayoutParams(0,-2,1));controls.add(c);c.setOnCheckedChangeListener((v,on)->invalidatePlan());return c;}
 private void show(String s){runOnUiThread(()->{if(!isDestroyed())status.setText(s);});}
 private void invalidatePlan(){ready=false;items.clear();page=0;if(move!=null){move.setEnabled(false);move.setAlpha(.4f);render();}}
 private void setBusy(boolean value){busy=value;for(View v:controls){v.setEnabled(!value);v.setAlpha(value?.5f:1f);}move.setEnabled(!value&&ready&&!items.isEmpty());move.setAlpha(move.isEnabled()?1f:.4f);clock.removeCallbacks(tick);if(value){startedAt=SystemClock.elapsedRealtime();clock.post(tick);}stopButton.setEnabled(value);stopButton.setAlpha(value?1f:.4f);previous.setEnabled(!value&&page>0);next.setEnabled(!value&&(page+1)*30<items.size());if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 private void pick(int code){if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,code);}
 @Override protected void onActivityResult(int r,int c,Intent data){
  super.onActivityResult(r,c,data);if(c!=RESULT_OK||data==null||data.getData()==null)return;
  Uri u=data.getData();if(!LOCAL.equals(u.getAuthority())){show("휴대폰 내부 저장소 또는 SD카드 폴더를 선택하세요. 클라우드 제공자는 사용하지 않습니다.");return;}
  int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  if((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0){show("파일 이동에 필요한 폴더 쓰기 권한을 허용해 주세요.");return;}
  try{getContentResolver().takePersistableUriPermission(u,flags);}catch(Exception ignored){}
  if(r==1)src=u;else if(r==2)dst=u;invalidatePlan();
  updatePaths();
 }
 private Uri root(Uri tree){return DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
 private String id(Uri u){return DocumentsContract.getDocumentId(u);}
 private void askScan(){
  if(busy)return;
  if(src==null||dst==null){show("원본과 대상 폴더를 먼저 선택하세요.");return;}
  if(!photos.isChecked()&&!videos.isChecked()&&!docs.isChecked()){show("한 가지 이상의 파일 종류를 선택하세요.");return;}
  new AlertDialog.Builder(this).setTitle("선택 폴더 접근 확인")
   .setMessage("선택한 폴더와 하위 폴더의 파일명·날짜를 읽고, 사진의 시각적 특징을 기기 안에서 분석합니다. 개인 문서나 사진이 포함될 수 있습니다. 문서 본문, 연락처, 계정에는 접근하지 않습니다. 외부 전송 없이 검색할까요?")
   .setNegativeButton("취소",null).setPositiveButton("접근 허용 · 검색",(dialog,which)->scan()).show();
 }
 private void scan(){
  stopped=false;ready=false;alreadySorted=0;items.clear();warnings.clear();page=0;render();setBusy(true);progress.setIndeterminate(true);phaseText.setText("파일 찾는 중");percentText.setText("—");countText.setText("발견 0개");show("선택한 폴더와 하위 폴더를 확인합니다.");
  boolean p=photos.isChecked(),v=videos.isChecked(),d=docs.isChecked();
  worker.execute(()->{
   ArrayList<Item> found=new ArrayList<>();boolean success=false;
   try {
    if(p)labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(.70f).build());
    ArrayList<Item> candidates=new ArrayList<>();walk(root(src),0,candidates,p,v,d,new HashSet<>());
    candidates.sort(Comparator.comparing((Item x)->x.name).thenComparing(x->x.uri.toString()));
     for(Item item:candidates){if(stopped)break;date(item);}
     HashMap<String,String> byDate=new HashMap<>();
     for(Item item:candidates){
      if(stopped)break;
      if(item.type==1&&!byDate.containsKey(item.date))byDate.put(item.date,photoLocation(item));
     }
     for(int n=0;n<candidates.size()&&!stopped;n++){
     Item item=candidates.get(n);paintProgress("날짜 · 특징 분석",n,candidates.size(),0,item.name);
     if(OrganizerRules.inOrganizedFolder(id(root(dst)),item.parentId,item.type)){alreadySorted++;paintProgress("정리됨 · 건너뜀",n+1,candidates.size(),0,item.name);continue;}
     item.feature=byDate.containsKey(item.date)?byDate.get(item.date):"위치미상";
      item.featureReason=item.type==1?"같은 날짜 첫 사진의 GPS 기준":"같은 날짜 사진의 위치 기준";
     if(OrganizerRules.alreadyAtDestination(item.parentId,id(root(dst)),item.folder()))alreadySorted++;else found.add(item);
     paintProgress("날짜 · 특징 분석",n+1,candidates.size(),0,item.name+"\n"+item.folder());
    }
    success=!stopped;
   }catch(Exception e){warnings.add("검색 중단: "+message(e));}
   finally{if(labeler!=null){labeler.close();labeler=null;}}
   final boolean complete=success;
   runOnUiThread(()->{
    items.addAll(found);items.sort(Comparator.comparing(Item::folder).thenComparing(i->i.name));ready=complete;
    progress.setIndeterminate(false);if(complete){progress.setProgress(1000);percentText.setText("100%");}phaseText.setText(complete?"분류 완료":"검색 중지");countText.setText("이동 대상 "+items.size()+"개 · 정리됨 "+alreadySorted+"개");setBusy(false);render();
    Set<String> folders=new HashSet<>();for(Item i:items)folders.add(i.folder());
    show((complete?"검색 완료":"검색 중지 — 다시 검색하세요")+": "+items.size()+"개 / "+folders.size()+"개 폴더\n"+(warnings.isEmpty()?"이미 정리된 "+alreadySorted+"개는 제외했습니다. 폴더명을 검토한 후 이동하세요.":"주의 "+warnings.size()+"건: "+warnings.get(0)+"\n읽지 못한 파일은 이동 대상에서 제외됩니다."));
   });
  });
 }
 private void walk(Uri parent,int depth,ArrayList<Item> found,boolean photosOn,boolean videosOn,boolean docsOn,Set<String> visited)throws Exception {
  if(stopped||OrganizerRules.excludeDestination(id(root(src)),id(root(dst)),id(parent))||!visited.add(id(parent)))return;
  if(depth>64){warnings.add("하위 폴더 깊이 제한");return;}
  Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(src,id(parent));
  ArrayList<Uri> dirs=new ArrayList<>();ArrayList<Item> files=new ArrayList<>();
  try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
   if(c==null)throw new IOException("폴더 읽기 실패");
   while(c.moveToNext()&&!stopped){
    String name=c.getString(1),mime=c.getString(2);if(name==null)continue;
    Uri u=DocumentsContract.buildDocumentUriUsingTree(src,c.getString(0));
    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){if(!name.equals("Android")&&!name.startsWith("."))dirs.add(u);continue;}
    int type=OrganizerRules.type(name);if(type==0||type==1&&!photosOn||type==2&&!videosOn||type==3&&!docsOn)continue;
    Item i=new Item();i.uri=u;i.parentId=id(parent);i.name=name;i.mime=mime;i.type=type;i.modified=c.isNull(3)?0:c.getLong(3);i.size=c.isNull(4)?-1:c.getLong(4);files.add(i);
   }
  }catch(Exception e){warnings.add("폴더 읽기 실패: "+message(e));return;}
  for(Item i:files){if(stopped)return;found.add(i);}final int discovered=found.size();runOnUiThread(()->{countText.setText("발견 "+discovered+"개");status.setText("파일 목록 확인 중 · "+id(parent));});
  for(Uri dir:dirs){if(stopped)return;walk(dir,depth+1,found,photosOn,videosOn,docsOn,visited);}
 }
 private void date(Item i){
  if(i.type==1)try(InputStream in=getContentResolver().openInputStream(i.uri)){
   if(in!=null){ExifInterface exif=new ExifInterface(in);i.date=OrganizerRules.metadataDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));if(i.date!=null)i.reason="사진 촬영일";}
  }catch(Exception ignored){}
  if(i.type==2){MediaMetadataRetriever mm=new MediaMetadataRetriever();try{mm.setDataSource(this,i.uri);String raw=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);i.date=OrganizerRules.metadataDate(raw);if(i.date!=null)i.reason="영상 기록일";}catch(Exception ignored){}finally{try{mm.release();}catch(Exception ignored){}}}
  if(i.date==null){i.date=OrganizerRules.filenameDate(i.name);if(i.date!=null)i.reason="파일명 날짜";}
  if(i.date==null&&i.modified>0){i.date=new SimpleDateFormat("yyMMdd",Locale.KOREA).format(new Date(i.modified));i.reason="파일 수정일";}
  if(i.date==null){i.date="날짜미상";i.reason="날짜 정보 없음";}
 }
 private String photoLocation(Item item){
   try(InputStream in=getContentResolver().openInputStream(item.uri)){
    if(in==null)return "위치미상";
    ExifInterface exif=new ExifInterface(in);float[] latLong=new float[2];
    if(!exif.getLatLong(latLong)||!Geocoder.isPresent())return "위치미상";
    Geocoder geocoder=new Geocoder(this,Locale.KOREA);
    List<Address> addresses=geocoder.getFromLocation(latLong[0],latLong[1],1);
    if(addresses==null||addresses.isEmpty())return "위치미상";
    Address address=addresses.get(0);
    String city=address.getLocality();
    if(city==null||city.isEmpty())city=address.getSubAdminArea();
    String dong=address.getSubLocality();
    if(dong==null||dong.isEmpty())dong=address.getThoroughfare();
    if(city==null||city.isEmpty()||dong==null||dong.isEmpty())return "위치미상";
    return OrganizerRules.safeFeature(city+dong);
   }catch(Exception e){return "위치미상";}
  }
  private void classify(Item i){
  if(i.name.toLowerCase(Locale.ROOT).matches(".*(screenshot|screen_shot|스크린샷|화면캡처).*")){i.feature="스크린샷";i.featureReason="파일명 기준";return;}
  i.feature="장소미상";i.featureReason="장소 단서 없음 · 직접 수정 가능";Bitmap bitmap=null;
  try{
   BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
   try(InputStream in=getContentResolver().openInputStream(i.uri)){BitmapFactory.decodeStream(in,null,opts);}
   if(opts.outWidth<=0||opts.outHeight<=0)return;
   opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>1024)opts.inSampleSize*=2;
   opts.inJustDecodeBounds=false;opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
   try(InputStream in=getContentResolver().openInputStream(i.uri)){bitmap=BitmapFactory.decodeStream(in,null,opts);}
   if(bitmap==null)return;
   int degrees=0;try(InputStream in=getContentResolver().openInputStream(i.uri)){int o=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);if(o==3||o==4)degrees=180;else if(o==5||o==6)degrees=90;else if(o==7||o==8)degrees=270;}catch(Exception ignored){}
   List<ImageLabel> labels=Tasks.await(labeler.process(InputImage.fromBitmap(bitmap,degrees)));
   float best=0;for(ImageLabel l:labels){String category=OrganizerRules.label(l.getText());if(category!=null&&l.getConfidence()>best){best=l.getConfidence();i.feature=category;i.featureReason="기기 내 분석 "+Math.round(best*100)+"% · 확인 필요";}}
  }catch(Exception e){i.featureReason="분석 불가 · 직접 수정 가능";}
  finally{if(bitmap!=null)bitmap.recycle();}
 }
 private void render(){
  rows.removeAllViews();emptyText.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);int pages=(items.size()+29)/30;page=Math.max(0,Math.min(page,Math.max(0,pages-1)));
  pageText.setText((pages==0?0:page+1)+" / "+pages);previous.setEnabled(!busy&&page>0);next.setEnabled(!busy&&page+1<pages);
  for(int j=page*30;j<Math.min(items.size(),page*30+30);j++){
   Item i=items.get(j);TextView row=text(i.name+"\n→ "+i.folder()+"\n"+i.reason+(i.type==1?" · "+i.featureReason:""),13);
   row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setBackground(tint(Color.rgb(247,249,252)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);rows.addView(row,lp);
   if(i.type==1)row.setOnClickListener(v->{if(!busy&&ready)edit(i);});
  }
 }
 private void edit(Item i){
  String old=i.folder();LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(20),dp(8),dp(20),0);EditText input=new EditText(this);input.setSingleLine(true);input.setText(i.feature);l.addView(input);CheckBox group=new CheckBox(this);group.setText("같은 날짜의 모든 파일에 적용");group.setChecked(true);l.addView(group);
  new AlertDialog.Builder(this).setTitle(i.date+"-요약장소 수정").setView(l).setNegativeButton("취소",null).setPositiveButton("적용",(d,w)->{String f=OrganizerRules.safeFeature(input.getText().toString());for(Item x:items)if(x==i||(group.isChecked()&&x.date.equals(i.date))){x.feature=f;x.featureReason="직접 지정";}render();}).show();
 }
 private void askMove(){
  if(busy||!ready||items.isEmpty())return;
  new AlertDialog.Builder(this).setTitle(items.size()+"개 파일 이동 확인")
   .setMessage("표시된 날짜별 폴더를 만들고 파일을 옮깁니다. 복사본과 원본의 SHA-256 검증 후 원본을 삭제합니다. 같은 이름은 번호를 붙여 보존합니다. 자동 분류와 날짜를 검토하셨습니까?\n\n정리 중에는 앱 화면을 유지해 주세요.")
   .setNegativeButton("다시 검토",null).setPositiveButton("확인 · 이동 실행",(d,w)->process()).show();
 }
 private void process(){
  stopped=false;ready=false;setBusy(true);progress.setIndeterminate(false);progress.setProgress(0);percentText.setText("0%");phaseText.setText("이동 준비");final ArrayList<Item> plan=new ArrayList<>(items);
  worker.execute(()->{
   int done=0,copiedOnly=0,failed=0,skipped=0,processed=0;ArrayList<String> errors=new ArrayList<>();HashMap<String,Uri> folders=new HashMap<>();HashMap<String,Set<String>> names=new HashMap<>();
   for(int index=0;index<plan.size();index++){
    if(stopped)break;Item i=plan.get(index);Uri target=null;boolean verified=false,sourceDeleted=false;final int position=index;
    try{
     if(OrganizerRules.alreadyAtDestination(i.parentId,id(root(dst)),i.folder())){skipped++;processed++;paintProgress("정리됨 · 건너뜀",processed,plan.size(),0,i.name);continue;}
     paintProgress("복사 중",index,plan.size(),0,i.name+"\n→ "+i.folder());
     assertUnchanged(i);
     Uri dir=folders.get(i.folder());if(dir==null){dir=ensure(root(dst),i.folder());folders.put(i.folder(),dir);names.put(i.folder(),names(dir));}
     Set<String> used=names.get(i.folder());String base=i.name.replace('/','_');String name=base;int k=0;while(used.contains(name))name=OrganizerRules.uniqueName(base,++k);
     if(stopped)break;
     target=DocumentsContract.createDocument(getContentResolver(),dir,i.mime==null?"application/octet-stream":i.mime,name);if(target==null)throw new IOException("대상 파일 생성 실패");used.add(name);
     checkpoint("복사 중",i,target);
     final Uri destination=target;
     VerifiedCopy.Result result;
     try(OutputStream out=getContentResolver().openOutputStream(target,"w")){
      result=VerifiedCopy.transfer(()->getContentResolver().openInputStream(i.uri),out,()->stopped,count->{long now=SystemClock.elapsedRealtime();if(now-lastPaint>150){lastPaint=now;paintProgress("복사 중",position,plan.size(),i.size>0?Math.min(1,count/(double)i.size)/3:0,i.name+"\n"+bytes(count)+(i.size>=0?" / "+bytes(i.size):""));}});
     }
     if(i.size>=0&&result.bytes!=i.size)throw new IOException("원본 크기 변경 감지");
     paintProgress("복사본 · 원본 검증",index,plan.size(),1.0/3,i.name+"\n복사 완료 · 내용 일치 여부를 확인합니다.");
     VerifiedCopy.verify(result,()->getContentResolver().openInputStream(destination),()->getContentResolver().openInputStream(i.uri),()->stopped,count->{long now=SystemClock.elapsedRealtime();if(now-lastPaint>150){lastPaint=now;paintProgress("복사본 · 원본 검증",position,plan.size(),(1+(result.bytes>0?Math.min(2,count/(double)result.bytes):0))/3,i.name+"\n검증 "+bytes(count)+" / "+bytes(result.bytes*2));}});
     assertUnchanged(i);verified=true;checkpoint("검증 완료 · 원본 삭제 전",i,target);
     if(stopped){copiedOnly++;processed++;break;}
     // A verified destination is never removed, even if source deletion throws after succeeding.
     if(!DocumentsContract.deleteDocument(getContentResolver(),i.uri))throw new IOException("원본 삭제 거절 — 양쪽 파일 보존");
     sourceDeleted=true;done++;checkpoint("이동 완료",i,target);getPreferences(0).edit().remove("pending").commit();
    }catch(Exception e){
     if(!sourceDeleted){if(verified)copiedOnly++;else failed++;}
     String detail=i.name+": "+message(e);
     if(target!=null&&!verified){try{if(!DocumentsContract.deleteDocument(getContentResolver(),target))detail+=" / 미완성 복사본 정리 필요";}catch(Exception cleanup){detail+=" / 미완성 복사본 정리 필요";}}
     if(errors.size()<20)errors.add(detail);
    }
    processed++;paintProgress("파일 이동",processed,plan.size(),0,"이동 "+done+"개 · 건너뜀 "+skipped+"개 · 오류 "+failed+"개");
   }
   final String summary=(stopped?"중지됨":"작업 완료")+"\n이동 "+done+"개 · 정리됨 "+skipped+"개 · 원본 삭제 미확인 "+copiedOnly+"개 · 미이동/오류 "+failed+"개\n"+"대상 "+plan.size()+"개 중 미처리 "+(plan.size()-done-copiedOnly-failed-skipped)+"개"+(errors.isEmpty()?"":"\n"+String.join("\n",errors))+"\n다시 작업하려면 새로 검색하세요.";
   runOnUiThread(()->{setBusy(false);phaseText.setText(stopped?"작업 중지":"작업 완료");show(summary);new AlertDialog.Builder(this).setTitle(stopped?"중지 결과":"정리 결과").setMessage(summary).setPositiveButton("확인",null).show();});
  });
 }
 private void assertUnchanged(Item i)throws Exception {
  try(Cursor c=getContentResolver().query(i.uri,new String[]{DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
   if(c==null||!c.moveToFirst())throw new IOException("원본이 없거나 접근할 수 없습니다");
   if(i.size>=0&&!c.isNull(0)&&c.getLong(0)!=i.size)throw new IOException("검색 후 파일 크기 변경 — 다시 검색하세요");
   if(i.modified>0&&!c.isNull(1)&&c.getLong(1)!=i.modified)throw new IOException("검색 후 수정된 파일 — 다시 검색하세요");
  }
 }
 private Set<String> names(Uri parent)throws Exception {
  HashSet<String> result=new HashSet<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(dst,id(parent));
  try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c==null)throw new IOException("대상 폴더 읽기 실패");while(c.moveToNext())result.add(c.getString(0));}return result;
 }
 private Uri ensure(Uri parent,String name)throws Exception {
  Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(dst,id(parent));
  try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
   if(c==null)throw new IOException("대상 폴더 읽기 실패");while(c.moveToNext())if(name.equals(c.getString(1))){if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))throw new IOException("폴더와 같은 이름의 파일이 존재합니다");return DocumentsContract.buildDocumentUriUsingTree(dst,c.getString(0));}
  }
  Uri dir=DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,name);if(dir==null)throw new IOException("폴더 생성 실패");return dir;
 }
 private void checkpoint(String stage,Item i,Uri target)throws Exception {
  String brief=stage+"\n"+i.name+" → "+i.folder();if(!getPreferences(0).edit().putString("pending",brief).commit())throw new IOException("작업 상태 저장 실패");
  JSONObject json=new JSONObject();json.put("time",System.currentTimeMillis());json.put("stage",stage);json.put("source",i.uri.toString());json.put("destination",target.toString());
  try(FileOutputStream out=openFileOutput("move-journal.jsonl",MODE_APPEND)){out.write((json.toString()+"\n").getBytes("UTF-8"));out.getFD().sync();}
 }
 private String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 @Override protected void onStop(){super.onStop();if(busy)stopped=true;}
 @Override public void onBackPressed(){if(busy){stopped=true;show("중지 중입니다. 완료 후 종료해 주세요.");}else super.onBackPressed();}
 @Override protected void onDestroy(){stopped=true;clock.removeCallbacks(tick);worker.shutdown();super.onDestroy();}
}

