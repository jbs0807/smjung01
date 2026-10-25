package com.example.dateorganizer;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public final class OrganizerRules {
 private OrganizerRules() {}
 public static int type(String n) {
  String s=n.toLowerCase(Locale.ROOT);
  if(s.matches(".*\\.(jpg|jpeg|png|heic|heif|webp|gif|bmp)$"))return 1;
  if(s.matches(".*\\.(mp4|mov|avi|mkv|3gp|webm|m4v|mts)$"))return 2;
  if(s.matches(".*\\.(pdf|hwp|hwpx|doc|docx|xls|xlsx|ppt|pptx|txt|csv|rtf|odt|ods|odp)$"))return 3;
  return 0;
 }
 public static String ymd(int y,int m,int d) {
  try {if(y<1900||y>2199)return null;return LocalDate.of(y,m,d).format(DateTimeFormatter.ofPattern("yyMMdd"));}catch(Exception e){return null;}
 }
 public static String metadataDate(String raw) {
  if(raw==null)return null;
  Matcher m=Pattern.compile("^(\\d{4})[-:]?(\\d{2})[-:]?(\\d{2})").matcher(raw);
  if(!m.find())return null;
  return ymd(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
 }
 public static String filenameDate(String raw) {
  Matcher m=Pattern.compile("(?<!\\d)((?:19|20|21)\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?!\\d)|(?<!\\d)((?:19|20|21)\\d{6})(?=\\d{6}(?:\\D|$))").matcher(raw);
  while(m.find()) {
   String d=m.group(4)!=null?metadataDate(m.group(4)):ymd(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
   if(d!=null)return d;
  }
  return null;
 }
 public static String safeFeature(String raw) {
  String s=raw.trim().replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_").replaceAll("^[. ]+|[. ]+$","");
  if(s.isEmpty())s="기타사진";
  return s.substring(0,Math.min(32,s.length()));
 }
 public static String uniqueName(String name,int number) {
  if(number==0)return name;
  int p=name.lastIndexOf('.');if(p<=0)p=name.length();
  return name.substring(0,p)+"_"+number+name.substring(p);
 }
 public static boolean within(String parent,String child) {
  return child.equals(parent)||child.startsWith(parent.endsWith(":")?parent:parent+"/");
 }
 public static String label(String raw) {
  String l=raw.toLowerCase(Locale.ROOT);
  if(Arrays.asList("beach","sea","ocean","coast","surfing","reef").contains(l))return "바다";
  if(Arrays.asList("food","dish","cuisine","meal","dessert","cake","fruit","vegetable","bread","noodle","sushi","pizza").contains(l))return "음식";
  if(Arrays.asList("flower","petal","bouquet").contains(l))return "꽃";
  if(Arrays.asList("mountain","mountain range","hill","cliff").contains(l))return "산";
  if(Arrays.asList("forest","tree","plant","garden","park").contains(l))return "숲과공원";
  if(Arrays.asList("dog","cat","pet","bird","animal","wildlife","fish").contains(l))return "동물";
  if(Arrays.asList("person","people","human","portrait","selfie","baby","child","bride","groom").contains(l))return "인물";
  if(Arrays.asList("car","vehicle","motorcycle","truck","train","aircraft","airplane","boat","ship").contains(l))return "탈것";
  if(Arrays.asList("building","architecture","city","skyscraper","tower","bridge","house").contains(l))return "건물";
  if(Arrays.asList("sunset","sunrise").contains(l))return "노을";
  if(Arrays.asList("lake","river","waterfall").contains(l))return "강과호수";
  if(Arrays.asList("document","receipt","text","handwriting").contains(l))return "문서촬영";
  if(Arrays.asList("computer","laptop","mobile phone","electronic device").contains(l))return "전자기기";
  if(Arrays.asList("snow","winter").contains(l))return "눈풍경";
  if(Arrays.asList("sky","cloud").contains(l))return "하늘";
  return null;
 }
}
