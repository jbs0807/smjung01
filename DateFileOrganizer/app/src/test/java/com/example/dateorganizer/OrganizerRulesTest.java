package com.example.dateorganizer;
import org.junit.Test;
import static org.junit.Assert.*;
public class OrganizerRulesTest {
 @Test public void datesUseRealCalendar(){assertEquals("240229",OrganizerRules.metadataDate("2024:02:29 23:59:00"));assertNull(OrganizerRules.metadataDate("2026:02:29 12:00:00"));assertNull(OrganizerRules.metadataDate("0000:01:01 00:00:00"));}
 @Test public void cameraAndScreenshotNames(){assertEquals("261025",OrganizerRules.filenameDate("IMG_20261025_160614.jpg"));assertEquals("261025",OrganizerRules.filenameDate("20261025160614.mp4"));assertEquals("261025",OrganizerRules.filenameDate("Screenshot_2026-10-25_test.png"));assertNull(OrganizerRules.filenameDate("1234567890123456.pdf"));assertNull(OrganizerRules.filenameDate("20260230.doc"));}
 @Test public void namesCannotEscapeDestination(){assertEquals("바다_여행",OrganizerRules.safeFeature("  바다/여행  "));assertEquals("기타사진",OrganizerRules.safeFeature(".."));assertFalse(OrganizerRules.safeFeature("../secret").contains("/"));}
 @Test public void collisionsKeepExtensions(){assertEquals("photo_1.jpg",OrganizerRules.uniqueName("photo.jpg",1));assertEquals("report_2",OrganizerRules.uniqueName("report",2));}
 @Test public void ancestryUsesBoundaries(){assertTrue(OrganizerRules.within("primary:","primary:DCIM"));assertTrue(OrganizerRules.within("primary:DCIM","primary:DCIM/Camera"));assertFalse(OrganizerRules.within("primary:DCIM","primary:DCIM2"));assertFalse(OrganizerRules.within("primary:","1234:DCIM"));}
 @Test public void labelsAreVisualNotIdentity(){assertEquals("바다",OrganizerRules.label("Beach"));assertEquals("인물",OrganizerRules.label("Person"));assertNull(OrganizerRules.label("unknown"));}
 @Test public void fileTypes(){assertEquals(1,OrganizerRules.type("a.HEIC"));assertEquals(2,OrganizerRules.type("a.MOV"));assertEquals(3,OrganizerRules.type("a.hwpx"));assertEquals(0,OrganizerRules.type("a.apk"));}
}
