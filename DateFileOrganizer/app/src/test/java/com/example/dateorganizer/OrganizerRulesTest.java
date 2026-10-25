package com.example.dateorganizer;
import org.junit.Test;
import static org.junit.Assert.*;
public class OrganizerRulesTest {
 @Test public void datesUseRealCalendar(){assertEquals("20240229",OrganizerRules.metadataDate("2024:02:29 23:59:00"));assertNull(OrganizerRules.metadataDate("2026:02:29 12:00:00"));assertNull(OrganizerRules.metadataDate("0000:01:01 00:00:00"));}
 @Test public void cameraAndScreenshotNames(){assertEquals("20261025",OrganizerRules.filenameDate("IMG_20261025_160614.jpg"));assertEquals("20261025",OrganizerRules.filenameDate("20261025160614.mp4"));assertEquals("20261025",OrganizerRules.filenameDate("Screenshot_2026-10-25_test.png"));assertNull(OrganizerRules.filenameDate("1234567890123456.pdf"));assertNull(OrganizerRules.filenameDate("20260230.doc"));}
 @Test public void namesCannotEscapeDestination(){assertEquals("바다_여행",OrganizerRules.safeFeature("  바다/여행  "));assertEquals("장소미상",OrganizerRules.safeFeature(".."));assertFalse(OrganizerRules.safeFeature("../secret").contains("/"));}
 @Test public void collisionsKeepExtensions(){assertEquals("photo_1.jpg",OrganizerRules.uniqueName("photo.jpg",1));assertEquals("report_2",OrganizerRules.uniqueName("report",2));}
 @Test public void ancestryUsesBoundaries(){assertTrue(OrganizerRules.within("primary:","primary:DCIM"));assertTrue(OrganizerRules.within("primary:DCIM","primary:DCIM/Camera"));assertFalse(OrganizerRules.within("primary:DCIM","primary:DCIM2"));assertFalse(OrganizerRules.within("primary:","1234:DCIM"));}
 @Test public void labelsAreVisualNotIdentity(){assertEquals("해변",OrganizerRules.label("Beach"));assertEquals("인물",OrganizerRules.label("Person"));assertNull(OrganizerRules.label("unknown"));}
 @Test public void fileTypes(){assertEquals(1,OrganizerRules.type("a.HEIC"));assertEquals(2,OrganizerRules.type("a.MOV"));assertEquals(3,OrganizerRules.type("a.hwpx"));assertEquals(0,OrganizerRules.type("a.apk"));}

 @Test public void sameSourceAndDestinationStillScansRoot(){assertFalse(OrganizerRules.excludeDestination("primary:Pictures","primary:Pictures","primary:Pictures"));}
 @Test public void nestedDestinationExcludedWithoutHidingOtherFolders(){assertTrue(OrganizerRules.excludeDestination("primary:Pictures","primary:Pictures/Sorted","primary:Pictures/Sorted"));assertFalse(OrganizerRules.excludeDestination("primary:Pictures","primary:Pictures/Sorted","primary:Pictures/Sorted2"));}
 @Test public void parentAndDifferentVolumeDestinationsAllowed(){assertFalse(OrganizerRules.excludeDestination("primary:Pictures/Camera","primary:Pictures","primary:Pictures/Camera"));assertFalse(OrganizerRules.excludeDestination("primary:Pictures","1234:Sorted","primary:Pictures"));}
 @Test public void alreadySortedFileDoesNotCopyOntoItself(){assertTrue(OrganizerRules.alreadyAtDestination("primary:Pictures/261025-바다","primary:Pictures","261025-바다"));assertTrue(OrganizerRules.alreadyAtDestination("primary:261025-문서","primary:","261025-문서"));assertFalse(OrganizerRules.alreadyAtDestination("primary:Pictures/261025-바다2","primary:Pictures","261025-바다"));assertFalse(OrganizerRules.alreadyAtDestination("1234:Pictures/261025-바다","primary:Pictures","261025-바다"));}
 @Test public void hierarchyUsesFullYearAndUnpaddedMonth(){
  assertEquals("2026년/10월/261026-부산광역시해운대구",OrganizerRules.folderPath("20261026","부산광역시해운대구"));
  assertEquals("2026년/1월/260102-위치미상",OrganizerRules.folderPath("20260102","위치미상"));
  assertEquals("1999년/12월/991231-위치미상",OrganizerRules.folderPath("19991231","위치미상"));
  assertEquals("2099년/12월/991231-위치미상",OrganizerRules.folderPath("20991231","위치미상"));
  assertEquals("연도미상/월미상/날짜미상-위치미상",OrganizerRules.folderPath("날짜미상","위치미상"));
 }
 @Test public void onlyAdministrativeCityCountyDistrictIncluded(){
  assertEquals("부산광역시해운대구",OrganizerRules.districtFeature("부산광역시",null,"해운대구","재송동"));
  assertEquals("창원시성산구",OrganizerRules.districtFeature("경상남도",null,"창원시","성산구"));
  assertEquals("함안군",OrganizerRules.districtFeature("경상남도","함안군",null,"가야읍"));
  assertEquals("서울특별시중구",OrganizerRules.districtFeature("서울특별시",null,"서울특별시","중구"));
  assertEquals("위치미상",OrganizerRules.districtFeature(null,null,null,"중앙로"));
  assertEquals("위치미상",OrganizerRules.districtFeature(null,null,null,null));
 }
 @Test public void organizedHierarchySkipsAllMediaTypes(){
  for(int type=1;type<=3;type++)assertTrue(OrganizerRules.inOrganizedFolder("primary:Pictures","primary:Pictures/2026년/10월/261026-부산광역시해운대구",type));
  assertTrue(OrganizerRules.inOrganizedFolder("primary:","primary:2024년/2월/240229-위치미상",1));
  assertTrue(OrganizerRules.inOrganizedFolder("primary:","primary:연도미상/월미상/날짜미상-위치미상",3));
  assertTrue(OrganizerRules.alreadyAtDestination("primary:Pictures/2026년/10월/261026-위치미상","primary:Pictures","2026년/10월/261026-위치미상"));
 }
 @Test public void flatFoldersAreEligibleForNewHierarchy(){assertFalse(OrganizerRules.inOrganizedFolder("primary:Pictures","primary:Pictures/261025-바다",1));}
 @Test public void unrelatedInvalidOrMismatchedHierarchyNotSkipped(){
  for(String folder:new String[]{"2026년/10월/261032-바다","2026년/11월/261025-바다","2025년/10월/261025-바다","2026년/10월/261025-바다/nested","2026년/2월/260229-바다","2026년/10월/날짜미상-바다"})assertFalse(folder,OrganizerRules.inOrganizedFolder("primary:Pictures","primary:Pictures/"+folder,1));
  assertFalse(OrganizerRules.inOrganizedFolder("primary:Pictures","other:Pictures/2026년/10월/261025-바다",1));
 }
}
