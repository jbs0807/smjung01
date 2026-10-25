package com.example.dateorganizer;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
public class VerifiedCopyTest {
 @Test public void validatesBinaryAndEmptyFiles()throws Exception {
  for(int length:new int[]{0,1,65537,500000}){byte[] bytes=new byte[length];new Random(7).nextBytes(bytes);ByteArrayOutputStream out=new ByteArrayOutputStream();VerifiedCopy.Result r=VerifiedCopy.transfer(()->new ByteArrayInputStream(bytes),out,()->false,n->{});assertEquals(length,r.bytes);VerifiedCopy.verify(r,()->new ByteArrayInputStream(out.toByteArray()),()->new ByteArrayInputStream(bytes),()->false);}
 }
 @Test(expected=IOException.class) public void rejectsCorruptDestination()throws Exception {byte[] b={1,2};VerifiedCopy.Result r=VerifiedCopy.transfer(()->new ByteArrayInputStream(b),new ByteArrayOutputStream(),()->false,n->{});VerifiedCopy.verify(r,()->new ByteArrayInputStream(new byte[]{1,3}),()->new ByteArrayInputStream(b),()->false);}
 @Test(expected=IOException.class) public void rejectsChangedSource()throws Exception {byte[] b={1,2};VerifiedCopy.Result r=VerifiedCopy.transfer(()->new ByteArrayInputStream(b),new ByteArrayOutputStream(),()->false,n->{});VerifiedCopy.verify(r,()->new ByteArrayInputStream(b),()->new ByteArrayInputStream(new byte[]{4,5}),()->false);}
 @Test(expected=InterruptedIOException.class) public void cancellationStopsCopy()throws Exception {VerifiedCopy.transfer(()->new ByteArrayInputStream(new byte[10]),new ByteArrayOutputStream(),()->true,n->{});}
 @Test(expected=IOException.class) public void diskFullPropagates()throws Exception {VerifiedCopy.transfer(()->new ByteArrayInputStream(new byte[10]),new OutputStream(){public void write(int b)throws IOException{throw new IOException("full");}},()->false,n->{});}

 @Test public void verificationReportsBothReadPasses()throws Exception {byte[] b=new byte[150000];ByteArrayOutputStream out=new ByteArrayOutputStream();VerifiedCopy.Result result=VerifiedCopy.transfer(()->new ByteArrayInputStream(b),out,()->false,n->{});ArrayList<Long> counts=new ArrayList<>();VerifiedCopy.verify(result,()->new ByteArrayInputStream(b),()->new ByteArrayInputStream(b),()->false,counts::add);assertEquals(Long.valueOf(300000),counts.get(counts.size()-1));for(int i=1;i<counts.size();i++)assertTrue(counts.get(i)>=counts.get(i-1));}
 @Test(expected=InterruptedIOException.class) public void verificationCanBeStopped()throws Exception {byte[] b=new byte[150000];VerifiedCopy.Result result=VerifiedCopy.transfer(()->new ByteArrayInputStream(b),new ByteArrayOutputStream(),()->false,n->{});final boolean[] stop={false};VerifiedCopy.verify(result,()->new ByteArrayInputStream(b),()->new ByteArrayInputStream(b),()->stop[0],n->{stop[0]=true;});}
}
