package com.example.dateorganizer;

import android.app.Activity;
import android.app.Dialog;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Browses only persisted local tree grants. Never requests broader storage access. */
final class FolderPicker {
 interface Selected { void accept(Uri uri); }
 private static final String LOCAL="com.android.externalstorage.documents";
 private final Activity activity;
 private final Selected selected;
 private final Runnable connect;
 private final Dialog dialog;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ArrayList<Entry> entries=new ArrayList<>(),visible=new ArrayList<>();
 private final ArrayDeque<Uri> parents=new ArrayDeque<>();
 private final TextView path,notice;
 private final Button up,choose;
 private final EditText search;
 private final BaseAdapter adapter;
 private Uri current;
 private int generation;
 private boolean closed=false,loading=false;
 private static final class Entry {
  final Uri uri; final String label;
  Entry(Uri u,String s){uri=u;label=s;}
 }
 static Uri document(Activity a,Uri u){
  return DocumentsContract.isDocumentUri(a,u)?u:DocumentsContract.buildDocumentUriUsingTree(u,DocumentsContract.getTreeDocumentId(u));
 }
 static boolean permitted(Activity a,Uri selection){
  if(selection==null||!LOCAL.equals(selection.getAuthority()))return false;
  try {
   String id=DocumentsContract.getDocumentId(document(a,selection));
   for(UriPermission grant:a.getContentResolver().getPersistedUriPermissions()){
    Uri uri=grant.getUri();
    // The selection must retain the grant's tree URI, even when browsing a child.
    if(grant.isReadPermission()&&grant.isWritePermission()&&LOCAL.equals(uri.getAuthority())
      &&DocumentsContract.isTreeUri(uri)
      &&DocumentsContract.getTreeDocumentId(uri).equals(DocumentsContract.getTreeDocumentId(selection))
      &&OrganizerRules.within(DocumentsContract.getTreeDocumentId(uri),id))return true;
   }
  }catch(Exception ignored){}
  return false;
 }
 static String name(Activity a,Uri u){
  String id=DocumentsContract.getDocumentId(document(a,u));int colon=id.indexOf(':');
  if(colon<0)return id;
  String storage=id.startsWith("primary:")?"내장 저장소":"SD 카드";
  String suffix=id.substring(colon+1);return storage+(suffix.isEmpty()?"":" / "+suffix);
 }
 FolderPicker(Activity a,String title,Uri initial,Selected selected,Runnable connect){
  this.activity=a;this.selected=selected;this.connect=connect;
  dialog=new Dialog(a);
  dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
  LinearLayout base=column();base.setPadding(dp(16),dp(16),dp(16),dp(12));base.setBackgroundColor(Color.WHITE);
  LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);
  TextView label=text(title,20);label.setTypeface(null,Typeface.BOLD);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
  Button close=button("닫기",()->dialog.dismiss());heading.addView(close,new LinearLayout.LayoutParams(dp(64),dp(48)));base.addView(heading);
  TextView hint=text("연결된 폴더에서는 추가 허용 없이 선택합니다.",12);hint.setTextColor(0xff64748b);hint.setPadding(0,dp(4),0,dp(8));base.addView(hint);
  if(permitted(a,initial)){
   Button reuse=button("현재 폴더 사용",()->{selected.accept(initial);dialog.dismiss();});
   base.addView(reuse,new LinearLayout.LayoutParams(-1,dp(48)));
   TextView saved=text(name(a,initial),12);saved.setTextColor(0xff64748b);saved.setMaxLines(2);saved.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);base.addView(saved);
  }
  LinearLayout navigation=new LinearLayout(a);navigation.setGravity(Gravity.CENTER_VERTICAL);
  up=button("상위",this::back);navigation.addView(up,new LinearLayout.LayoutParams(dp(56),dp(48)));
  path=text("연결된 위치",13);path.setTextColor(0xff475569);path.setPadding(dp(10),0,0,0);path.setMaxLines(2);path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);navigation.addView(path,new LinearLayout.LayoutParams(0,-2,1));base.addView(navigation);
  search=new EditText(a);search.setSingleLine(true);search.setTextSize(14);search.setHint("현재 목록에서 폴더 찾기");base.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
  notice=text("",12);notice.setTextColor(0xff64748b);notice.setPadding(0,dp(6),0,dp(6));base.addView(notice);
  ListView list=new ListView(a);list.setDivider(new android.graphics.drawable.ColorDrawable(0xffe8edf3));list.setDividerHeight(dp(1));
  adapter=new BaseAdapter(){
   public int getCount(){return visible.size();}
   public Object getItem(int p){return visible.get(p);}
   public long getItemId(int p){return p;}
   public View getView(int p,View recycled,ViewGroup parent){
    TextView row=recycled instanceof TextView?(TextView)recycled:text("",15);
    row.setMinHeight(dp(48));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(8),dp(8),dp(8));
    row.setMaxLines(2);row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    row.setText(visible.get(p).label+"  ›");return row;
   }
  };
  list.setAdapter(adapter);list.setOnItemClickListener((p,v,index,id)->{if(loading)return;Entry e=visible.get(index);if(current!=null)parents.push(current);open(e.uri);});base.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  choose=button("이 폴더 선택",()->{if(current!=null&&!loading&&permitted(a,current)){selected.accept(current);dialog.dismiss();}});
  choose.setTextColor(Color.WHITE);GradientDrawable primary=new GradientDrawable();primary.setColor(0xff2563eb);primary.setCornerRadius(dp(10));choose.setBackground(primary);base.addView(choose,new LinearLayout.LayoutParams(-1,dp(48)));
  Button add=button("다른 위치 연결",()->{dialog.dismiss();connect.run();});LinearLayout.LayoutParams addLp=new LinearLayout.LayoutParams(-1,dp(48));addLp.topMargin=dp(6);base.addView(add,addLp);
  dialog.setContentView(base);
  dialog.setOnDismissListener(d->{closed=true;generation++;worker.shutdownNow();});
  dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP&&current!=null){back();return true;}return false;});
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter();}public void afterTextChanged(Editable e){}});
  dialog.show();Window w=dialog.getWindow();if(w!=null){int width=Math.min(a.getResources().getDisplayMetrics().widthPixels-dp(24),dp(560));int height=(int)(a.getResources().getDisplayMetrics().heightPixels*.86);w.setLayout(width,height);w.setBackgroundDrawableResource(android.R.color.transparent);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
  // Start at granted roots so sibling folders and other storage remain discoverable.
  roots();
 }
 private void roots(){
  generation++;loading=false;current=null;parents.clear();entries.clear();search.setText("");path.setText("연결된 위치");up.setEnabled(false);choose.setEnabled(false);choose.setAlpha(.4f);
  for(UriPermission p:activity.getContentResolver().getPersistedUriPermissions())if(permitted(activity,p.getUri()))entries.add(new Entry(document(activity,p.getUri()),name(activity,p.getUri())));
  entries.sort(Comparator.comparing(e->e.label));filter();notice.setText(entries.isEmpty()?"아직 연결한 위치가 없습니다. 아래에서 연결하세요.":"연결된 위치 "+entries.size()+"개 · 폴더를 눌러 열기");
 }
 private void back(){if(!parents.isEmpty())open(parents.pop());else roots();}
 private void open(Uri uri){
  if(!permitted(activity,uri)){notice.setText("접근 권한이 만료되었습니다. 위치를 다시 연결하세요.");return;}
  current=uri;int request=++generation;loading=true;entries.clear();filter();search.setText("");path.setText(name(activity,uri));up.setEnabled(true);choose.setEnabled(false);choose.setAlpha(.4f);notice.setText("폴더 불러오는 중…");
  worker.execute(()->{
   ArrayList<Entry> found=new ArrayList<>();String failure=null;
   try {
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,DocumentsContract.getDocumentId(uri));
    try(Cursor c=activity.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
     if(c==null)throw new java.io.IOException();
     while(c.moveToNext()&&!Thread.currentThread().isInterrupted())if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))){String n=c.getString(1);if(n!=null&&!n.startsWith(".")&&!n.equals("Android"))found.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(uri,c.getString(0)),n));}
    }
    found.sort(Comparator.comparing(e->e.label.toLowerCase(Locale.ROOT)));
   }catch(Exception e){failure="폴더를 읽을 수 없습니다. SD 카드 연결과 접근 권한을 확인하세요.";}
   final String error=failure;
   main.post(()->{if(closed||activity.isDestroyed()||request!=generation)return;loading=false;entries.clear();entries.addAll(found);filter();notice.setText(error!=null?error:found.isEmpty()?"하위 폴더가 없습니다. 현재 폴더를 선택할 수 있습니다.":"하위 폴더 "+found.size()+"개");choose.setEnabled(error==null);choose.setAlpha(error==null?1f:.4f);});
  });
 }
 private void filter(){String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();for(Entry e:entries)if(e.label.toLowerCase(Locale.ROOT).contains(q))visible.add(e);adapter.notifyDataSetChanged();if(!loading&&!q.isEmpty())notice.setText("검색 결과 "+visible.size()+"개");}
 private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
 private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
 private TextView text(String s,int size){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(0xff17243b);return t;}
 private Button button(String s,Runnable r){Button b=new Button(activity);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(0xff2563eb);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setOnClickListener(v->r.run());return b;}
}
