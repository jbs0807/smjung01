package com.example.dateorganizer;
import java.io.*;
import java.security.*;
import java.util.*;
public final class VerifiedCopy {
 public interface Source { InputStream open() throws Exception; }
 public interface Cancel { boolean stopped(); }
 public interface Progress { void bytes(long count); }
 public static final class Result { public final long bytes; public final byte[] digest; Result(long b,byte[] d){bytes=b;digest=d;} }
 static void check(Cancel c)throws IOException {if(c.stopped())throw new InterruptedIOException("중지 요청");}
 public static Result transfer(Source source,OutputStream out,Cancel cancel,Progress progress)throws Exception {
  MessageDigest md=MessageDigest.getInstance("SHA-256");long count=0;
  try(InputStream in=source.open()) {
   if(in==null||out==null)throw new IOException("파일을 열 수 없습니다");
   byte[] b=new byte[65536];int n;
   while((n=in.read(b))!=-1){check(cancel);out.write(b,0,n);md.update(b,0,n);count+=n;progress.bytes(count);}
   check(cancel);out.flush();return new Result(count,md.digest());
  }
 }
 public static byte[] hash(Source source,Cancel cancel)throws Exception {
  MessageDigest md=MessageDigest.getInstance("SHA-256");
  try(InputStream in=source.open()){if(in==null)throw new IOException("검증 파일 열기 실패");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check(cancel);md.update(b,0,n);}check(cancel);return md.digest();}
 }
 public static void verify(Result copy,Source destination,Source original,Cancel cancel)throws Exception {
  if(!Arrays.equals(copy.digest,hash(destination,cancel)))throw new IOException("복사본 검증 실패 — 원본 보존");
  if(!Arrays.equals(copy.digest,hash(original,cancel)))throw new IOException("원본 변경 감지 — 원본 보존");
 }
}
