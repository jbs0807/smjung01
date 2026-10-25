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
  try {if(y<1900||y>2199)return null;return LocalDate.of(y,m,d).format(DateTimeFormatter.BASIC_ISO_DATE);}catch(Exception e){return null;}
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
  if(s.isEmpty())s="장소미상";
  return s.substring(0,Math.min(32,s.length()));
 }
 public static String uniqueName(String name,int number) {
  if(number==0)return name;
  int p=name.lastIndexOf('.');if(p<=0)p=name.length();
  return name.substring(0,p)+"_"+number+name.substring(p);
 }
 public static String folderPath(String date,String feature) {
  String suffix="-"+safeFeature(feature);
  if("날짜미상".equals(date))return "연도미상/월미상/날짜미상"+suffix;
  LocalDate day=LocalDate.parse(date,DateTimeFormatter.BASIC_ISO_DATE);
  return day.getYear()+"년/"+day.getMonthValue()+"월/"+day.format(DateTimeFormatter.ofPattern("yyMMdd"))+suffix;
 }
 public static String districtFeature(String admin,String subAdmin,String locality,String subLocality) {
  LinkedHashSet<String> parts=new LinkedHashSet<>();
  // Administrative fields only: never fall back to street, building or a full address.
  for(String field:new String[]{admin,subAdmin,locality,subLocality}){
   if(field==null)continue;
   for(String word:field.trim().split("\\s+"))
    if(word.matches("[가-힣]+(?:시|군|구)"))parts.add(word);
  }
  return parts.isEmpty()?"위치미상":safeFeature(String.join("",parts));
 }
 public static boolean within(String parent,String child) {
  return child.equals(parent)||child.startsWith(parent.endsWith(":")?parent:parent+"/");
 }
 public static boolean excludeDestination(String source,String destination,String directory) {
  return !source.equals(destination)&&destination.equals(directory);
 }
 public static boolean inOrganizedFolder(String destination,String parent,int type) {
  String prefix=destination+(destination.endsWith(":")?"":"/");
  if(!parent.startsWith(prefix))return false;
  String folder=parent.substring(prefix.length());
  String[] levels=folder.split("/",-1);
  if(levels.length!=3)return false;
  folder=levels[2];
  int dash=folder.indexOf('-');if(dash<0||dash==folder.length()-1)return false;
  String date=folder.substring(0,dash),feature=folder.substring(dash+1);
  if(date.equals("날짜미상"))return levels[0].equals("연도미상")&&levels[1].equals("월미상")&&type>=1&&type<=3;
  if(!date.matches("[0-9]{6}")||!levels[0].matches("(?:19|20|21)[0-9]{2}년")||!levels[1].matches("(?:[1-9]|1[0-2])월"))return false;
  int year=Integer.parseInt(levels[0].substring(0,4));
  String full=ymd(year,Integer.parseInt(date.substring(2,4)),Integer.parseInt(date.substring(4,6)));
  return full!=null&&folderPath(full,feature).equals(parent.substring(prefix.length()))&&type>=1&&type<=3;
 }
 public static boolean alreadyAtDestination(String parent,String destination,String folder) {
  return parent.equals(destination+(destination.endsWith(":")?"":"/")+folder);
 }
 public static String label(String raw) {
  String l=raw.toLowerCase(Locale.ROOT);
  if(Arrays.asList("beach","sea","ocean","coast","surfing","reef").contains(l))return "해변";
  if(Arrays.asList("food","dish","cuisine","meal","dessert","cake","fruit","vegetable","bread","noodle","sushi","pizza").contains(l))return "음식";
  if(Arrays.asList("flower","petal","bouquet").contains(l))return "꽃";
  if(Arrays.asList("mountain","mountain range","hill","cliff").contains(l))return "산";
  if(Arrays.asList("forest","tree","plant","garden","park").contains(l))return "공원";
  if(Arrays.asList("dog","cat","pet","bird","animal","wildlife","fish").contains(l))return "동물";
  if(Arrays.asList("person","people","human","portrait","selfie","baby","child","bride","groom").contains(l))return "인물";
  if(Arrays.asList("car","vehicle","motorcycle","truck","train","aircraft","airplane","boat","ship").contains(l))return "탈것";
  if(Arrays.asList("building","architecture","city","skyscraper","tower","bridge","house").contains(l))return "건물";
  if(Arrays.asList("sunset","sunrise").contains(l))return "노을";
  if(Arrays.asList("lake","river","waterfall").contains(l))return "강호수";
  if(Arrays.asList("document","receipt","text","handwriting").contains(l))return "문서촬영";
  if(Arrays.asList("computer","laptop","mobile phone","electronic device").contains(l))return "전자기기";
  if(Arrays.asList("snow","winter").contains(l))return "눈풍경";
  if(Arrays.asList("sky","cloud").contains(l))return "하늘";
  return null;
 }
}


