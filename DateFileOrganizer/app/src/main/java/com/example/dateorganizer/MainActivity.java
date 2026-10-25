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
 private TextView status,paths,pageText;
 private LinearLayout rows;
 private ProgressBar progress;
 private Button move,scanButton,stopButton,previous,next;
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
 private final int ink=Color.rgb(21,45,67),teal=Color.rgb(0,123,109);
 private static class Item {
  Uri uri;String name,mime,date,reason,feature="",featureReason="";int type;long size,modified;
  String folder(){return date+"-"+(type==1?OrganizerRules.safeFeature(feature):type==2?"동영상":"문서");}
 }
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  getWindow().setStatusBarColor(ink);getWindow().setNavigationBarColor(ink);
  LinearLayout base=new LinearLayout(this);base.setOrientation(1);base.setBackgroundColor(Color.rgb(244,247,250));
  base.setPadding(dp(18),dp(12),dp(18),dp(10));
  base.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(18)+in.getSystemWindowInsetLeft(),dp(12)+in.getSystemWindowInsetTop(),dp(18)+in.getSystemWindowInsetRight(),dp(10)+in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});
  LinearLayout header=new LinearLayout(this);TextView title=text("날짜별 파일 정리",23);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button exit=button("×",()->{if(busy)stopped=true;else finish();});header.addView(exit,new LinearLayout.LayoutParams(dp(50),dp(48)));base.addView(header);
  ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);base.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  content.addView(text("사진 · 문서 · 동영상\n사진 내용을 기기 안에서 분석합니다",14));
  addControl(content,"1  찾을 폴더 선택",()->pick(1));
  addControl(content,"2  정리할 대상 폴더 선택",()->pick(2));
  paths=text("원본 · 대상 폴더를 선택하세요",13);content.addView(paths);
  LinearLayout checks=new LinearLayout(this);photos=check("사진",checks);videos=check("동영상",checks);docs=check("문서",checks);content.addView(checks);
  scanButton=addControl(content,"3  검색 및 분류 미리보기",this::askScan);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);content.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
  status=text("예: 261025-바다 / 261025-문서\n촬영일 → 파일명 날짜 → 수정일 순서로 적용합니다.",14);status.setPadding(0,dp(10),0,dp(10));content.addView(status);
  content.addView(text("사진 항목을 누르면 특징 이름을 수정할 수 있습니다.\n하위 폴더도 검색하며, 대상 폴더는 검색에서 제외합니다.",12));
  rows=new LinearLayout(this);rows.setOrientation(1);content.addView(rows);
  LinearLayout pages=new LinearLayout(this);previous=button("이전",()->{if(page>0){page--;render();}});next=button("다음",()->{page++;render();});pageText=text("0 / 0",14);pageText.setGravity(Gravity.CENTER);
  pages.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));pages.addView(pageText,new LinearLayout.LayoutParams(0,dp(48),1));pages.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));content.addView(pages);
  move=button("4  확인 후 이동",this::askMove);move.setTextColor(Color.WHITE);move.setBackground(tint(teal));base.addView(move,new LinearLayout.LayoutParams(-1,dp(58)));
  stopButton=button("작업 중지",()->{stopped=true;stopButton.setEnabled(false);show("중지 중… 현재 원본을 보존하고 안전하게 멈춥니다.");});base.addView(stopButton,new LinearLayout.LayoutParams(-1,dp(48)));
  setContentView(base);setBusy(false);render();
  String pending=getPreferences(0).getString("pending","");
  if(!pending.isEmpty())show("이전 작업이 중단되었습니다. 원본/대상 파일을 확인한 뒤 다시 검색하세요.\n"+pending+"\n검증 전 파일의 원본은 삭제하지 않습니다.");
 }
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(ink);return t;}
 private GradientDrawable tint(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(12));return d;}
 private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 private Button addControl(LinearLayout l,String s,Runnable r){Button b=button(s,r);l.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));controls.add(b);return b;}
 private CheckBox check(String name,LinearLayout l){CheckBox c=new CheckBox(this);c.setText(name);c.setChecked(true);c.setTextColor(ink);l.addView(c,new LinearLayout.LayoutParams(0,-2,1));controls.add(c);c.setOnCheckedChangeListener((v,on)->invalidatePlan());return c;}
 private void show(String s){runOnUiThread(()->{if(!isDestroyed())status.setText(s);});}
 private void invalidatePlan(){ready=false;items.clear();page=0;if(move!=null){move.setEnabled(false);render();}}
 private void setBusy(boolean value){busy=value;for(View v:controls)v.setEnabled(!value);move.setEnabled(!value&&ready&&!items.isEmpty());stopButton.setEnabled(value);previous.setEnabled(!value&&page>0);next.setEnabled(!value&&(page+1)*30<items.size());if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 private void pick(int code){if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,code);}
 @Override protected void onActivityResult(int r,int c,Intent data){
  super.onActivityResult(r,c,data);if(c!=RESULT_OK||data==null||data.getData()==null)return;
  Uri u=data.getData();if(!LOCAL.equals(u.getAuthority())){show("휴대폰 내부 저장소 또는 SD카드 폴더를 선택하세요. 클라우드 제공자는 사용하지 않습니다.");return;}
  int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  if((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0){show("파일 이동에 필요한 폴더 쓰기 권한을 허용해 주세요.");return;}
  try{getContentResolver().takePersistableUriPermission(u,flags);}catch(Exception ignored){}
  if(r==1)src=u;else if(r==2)dst=u;invalidatePlan();
  paths.setText("원본: "+(src==null?"미선택":DocumentsContract.getTreeDocumentId(src))+"\n대상: "+(dst==null?"미선택":DocumentsContract.getTreeDocumentId(dst)));
 }
 private Uri root(Uri tree){return DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
 private String id(Uri u){return DocumentsContract.getDocumentId(u);}
 private void askScan(){
  if(busy)return;
  if(src==null||dst==null){show("원본과 대상 폴더를 먼저 선택하세요.");return;}
  String s=id(root(src)),d=id(root(dst));
  if(OrganizerRules.within(d,s)){show("대상 폴더는 원본과 같거나 원본의 상위 폴더일 수 없습니다. 별도 폴더를 선택하세요.");return;}
  if(!photos.isChecked()&&!videos.isChecked()&&!docs.isChecked()){show("한 가지 이상의 파일 종류를 선택하세요.");return;}
  new AlertDialog.Builder(this).setTitle("선택 폴더 접근 확인")
   .setMessage("선택한 폴더와 하위 폴더의 파일명·날짜를 읽고, 사진의 시각적 특징을 기기 안에서 분석합니다. 개인 문서나 사진이 포함될 수 있습니다. 문서 본문, 연락처, 계정에는 접근하지 않습니다. 외부 전송 없이 검색할까요?")
   .setNegativeButton("취소",null).setPositiveButton("접근 허용 · 검색",(dialog,which)->scan()).show();
 }
 private void scan(){
  stopped=false;ready=false;items.clear();warnings.clear();page=0;render();setBusy(true);progress.setIndeterminate(true);
  boolean p=photos.isChecked(),v=videos.isChecked(),d=docs.isChecked();
  worker.execute(()->{
   ArrayList<Item> found=new ArrayList<>();boolean success=false;
   try {
    if(p)labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(.70f).build());
    walk(root(src),0,found,p,v,d,new HashSet<>());success=!stopped;
   }catch(Exception e){warnings.add("검색 중단: "+message(e));}
   finally{if(labeler!=null){labeler.close();labeler=null;}}
   final boolean complete=success;
   runOnUiThread(()->{
    items.addAll(found);items.sort(Comparator.comparing(Item::folder).thenComparing(i->i.name));ready=complete;
    progress.setIndeterminate(false);progress.setProgress(complete?100:0);setBusy(false);render();
    Set<String> folders=new HashSet<>();for(Item i:items)folders.add(i.folder());
    show((complete?"검색 완료":"검색 중지 — 다시 검색하세요")+": "+items.size()+"개 / "+folders.size()+"개 폴더\n"+(warnings.isEmpty()?"폴더명을 검토한 후 이동하세요.":"주의 "+warnings.size()+"건: "+warnings.get(0)+"\n읽지 못한 파일은 이동 대상에서 제외됩니다."));
   });
  });
 }
 private void walk(Uri parent,int depth,ArrayList<Item> found,boolean photosOn,boolean videosOn,boolean docsOn,Set<String> visited)throws Exception {
  if(stopped||id(parent).equals(id(root(dst)))||!visited.add(id(parent)))return;
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
    Item i=new Item();i.uri=u;i.name=name;i.mime=mime;i.type=type;i.modified=c.isNull(3)?0:c.getLong(3);i.size=c.isNull(4)?-1:c.getLong(4);files.add(i);
   }
  }catch(Exception e){warnings.add("폴더 읽기 실패: "+message(e));return;}
  for(Item i:files){if(stopped)return;date(i);if(i.type==1)classify(i);if(stopped)return;found.add(i);if(found.size()%5==0||found.size()==1)show("검색·분류 중 "+found.size()+"개\n"+i.folder());}
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
 private void classify(Item i){
  if(i.name.toLowerCase(Locale.ROOT).matches(".*(screenshot|screen_shot|스크린샷|화면캡처).*")){i.feature="스크린샷";i.featureReason="파일명 기준";return;}
  i.feature="기타사진";i.featureReason="분류 불확실 · 직접 수정 가능";Bitmap bitmap=null;
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
  rows.removeAllViews();int pages=(items.size()+29)/30;page=Math.max(0,Math.min(page,Math.max(0,pages-1)));
  pageText.setText((pages==0?0:page+1)+" / "+pages);previous.setEnabled(!busy&&page>0);next.setEnabled(!busy&&page+1<pages);
  for(int j=page*30;j<Math.min(items.size(),page*30+30);j++){
   Item i=items.get(j);TextView row=text(i.folder()+"\n"+i.name+"\n"+i.reason+(i.type==1?" · "+i.featureReason:""),14);
   row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setBackground(tint(Color.WHITE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);rows.addView(row,lp);
   if(i.type==1)row.setOnClickListener(v->{if(!busy&&ready)edit(i);});
  }
 }
 private void edit(Item i){
  String old=i.folder();LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(20),dp(8),dp(20),0);EditText input=new EditText(this);input.setSingleLine(true);input.setText(i.feature);l.addView(input);CheckBox group=new CheckBox(this);group.setText("같은 날짜·특징의 사진에 함께 적용");group.setChecked(true);l.addView(group);
  new AlertDialog.Builder(this).setTitle(i.date+"-사진특징 수정").setView(l).setNegativeButton("취소",null).setPositiveButton("적용",(d,w)->{String f=OrganizerRules.safeFeature(input.getText().toString());for(Item x:items)if(x==i||(group.isChecked()&&x.type==1&&x.folder().equals(old))){x.feature=f;x.featureReason="직접 지정";}render();}).show();
 }
 private void askMove(){
  if(busy||!ready||items.isEmpty())return;
  new AlertDialog.Builder(this).setTitle(items.size()+"개 파일 이동 확인")
   .setMessage("표시된 날짜별 폴더를 만들고 파일을 옮깁니다. 복사본과 원본의 SHA-256 검증 후 원본을 삭제합니다. 같은 이름은 번호를 붙여 보존합니다. 자동 분류와 날짜를 검토하셨습니까?\n\n정리 중에는 앱 화면을 유지해 주세요.")
   .setNegativeButton("다시 검토",null).setPositiveButton("확인 · 이동 실행",(d,w)->process()).show();
 }
 private void process(){
  stopped=false;ready=false;setBusy(true);progress.setIndeterminate(false);progress.setProgress(0);final ArrayList<Item> plan=new ArrayList<>(items);
  worker.execute(()->{
   int done=0,copiedOnly=0,failed=0;ArrayList<String> errors=new ArrayList<>();HashMap<String,Uri> folders=new HashMap<>();HashMap<String,Set<String>> names=new HashMap<>();
   for(int index=0;index<plan.size();index++){
    if(stopped)break;Item i=plan.get(index);Uri target=null;boolean verified=false,sourceDeleted=false;final int position=index;
    try{
     assertUnchanged(i);
     Uri dir=folders.get(i.folder());if(dir==null){dir=ensure(root(dst),i.folder());folders.put(i.folder(),dir);names.put(i.folder(),names(dir));}
     Set<String> used=names.get(i.folder());String base=i.name.replace('/','_');String name=base;int k=0;while(used.contains(name))name=OrganizerRules.uniqueName(base,++k);
     if(stopped)break;
     target=DocumentsContract.createDocument(getContentResolver(),dir,i.mime==null?"application/octet-stream":i.mime,name);if(target==null)throw new IOException("대상 파일 생성 실패");used.add(name);
     checkpoint("복사 중",i,target);
     final Uri destination=target;
     VerifiedCopy.Result result;
     try(OutputStream out=getContentResolver().openOutputStream(target,"w")){
      result=VerifiedCopy.transfer(()->getContentResolver().openInputStream(i.uri),out,()->stopped,count->{long now=SystemClock.elapsedRealtime();if(now-lastPaint>250){lastPaint=now;show("이동 "+(position+1)+" / "+plan.size()+"\n"+i.folder()+"\n복사 "+(count/1048576)+" MB"+ (i.size>0?" / "+(i.size/1048576)+" MB":""));}});
     }
     if(i.size>=0&&result.bytes!=i.size)throw new IOException("원본 크기 변경 감지");
     show("검증 "+(index+1)+" / "+plan.size()+"\n"+i.name);
     VerifiedCopy.verify(result,()->getContentResolver().openInputStream(destination),()->getContentResolver().openInputStream(i.uri),()->stopped);
     assertUnchanged(i);verified=true;checkpoint("검증 완료 · 원본 삭제 전",i,target);
     if(stopped){copiedOnly++;break;}
     // A verified destination is never removed, even if source deletion throws after succeeding.
     if(!DocumentsContract.deleteDocument(getContentResolver(),i.uri))throw new IOException("원본 삭제 거절 — 양쪽 파일 보존");
     sourceDeleted=true;done++;checkpoint("이동 완료",i,target);getPreferences(0).edit().remove("pending").commit();
    }catch(Exception e){
     if(!sourceDeleted){if(verified)copiedOnly++;else failed++;}
     String detail=i.name+": "+message(e);
     if(target!=null&&!verified){try{if(!DocumentsContract.deleteDocument(getContentResolver(),target))detail+=" / 미완성 복사본 정리 필요";}catch(Exception cleanup){detail+=" / 미완성 복사본 정리 필요";}}
     if(errors.size()<20)errors.add(detail);
    }
    final int completed=index+1;runOnUiThread(()->progress.setProgress(completed*100/plan.size()));
   }
   final String summary=(stopped?"중지됨":"작업 완료")+"\n이동 "+done+"개 · 원본 삭제 미확인 "+copiedOnly+"개 · 미이동/오류 "+failed+"개\n"+"대상 "+plan.size()+"개 중 미처리 "+(plan.size()-done-copiedOnly-failed)+"개"+(errors.isEmpty()?"":"\n"+String.join("\n",errors))+"\n다시 작업하려면 새로 검색하세요.";
   runOnUiThread(()->{setBusy(false);show(summary);});
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
 @Override protected void onDestroy(){stopped=true;worker.shutdown();super.onDestroy();}
}
