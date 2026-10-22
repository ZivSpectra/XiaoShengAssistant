package com.zhizhou.xxtassistant.data;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

public class DeadlineParserTest {
    @Test
    public void detailAnswerWindowUsesTheSecondDateAsDeadline() throws Exception {
        String html = "<div>作答时间：<em>2026-09-29 00:00</em> 至 <em>2026-10-05 23:59</em></div>";
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-10-05 23:59").getTime();
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html));
    }

    @Test
    public void detailPageMatchesRealChaoxingPreviewMarkup() throws Exception {
        String html = "<div class=\"infoHead\"><p>作答时间:<em>09-24 16:00</em>至<em>09-30 20:00</em></p></div>";
        long now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-10-06 10:00").getTime();
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-09-30 20:00").getTime();
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html, now));
    }

    @Test
    public void detailAnswerWindowWithoutYearUsesCurrentSessionYear() throws Exception {
        long now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-10-06 10:00").getTime();
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-09-30 20:00").getTime();
        String html = "<div>作答时间：<em>09-24 16:00</em> 至 <em>09-30 20:00</em></div>";
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html, now));
    }

    @Test
    public void yearlessAnswerWindowHandlesCrossYearDeadline() throws Exception {
        long now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-12-15 10:00").getTime();
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2027-01-05 23:59").getTime();
        String html = "<div>作答时间：12-20 08:00 至 01-05 23:59</div>";
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html, now));
    }

    @Test
    public void detailTimestampIsReadFromADataAttribute() {
        long expected = 1791235140000L;
        String html = "<script>window.task = { endTime: " + expected + " };</script>";
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html));
    }

    @Test
    public void officialWorkPaperFieldsAreReadWhenPageUsesEndDateNames() throws Exception {
        String html = "<input type=\"hidden\" name=\"workAnswerId\" value=\"56645031\">" +
                "<div><span>workEndTime</span><span>2026-10-14 23:59</span></div>";
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .parse("2026-10-14 23:59").getTime();
        assertEquals(expected, DeadlineParser.INSTANCE.extractFromDetail(html));
    }

    @Test
    public void detailPageDoesNotTurnIdentifiersIntoDates() {
        String html = "<input name=\"taskrefId\" value=\"49156357\"><div>作业要求：按时完成</div>";
        assertEquals(0L, DeadlineParser.INSTANCE.extractFromDetail(html));
    }

    @Test
    public void listRelativeTimeKeepsZeroUnknownAndParsesMixedUnits() {
        long now = 1800000000000L;
        long parsed = DeadlineParser.INSTANCE.parseTaskEndTime("剩余 1天 2小时 3分钟", "", now);
        assertEquals(now + 86400000L + 2 * 3600000L + 3 * 60000L, parsed);
        assertTrue(DeadlineParser.INSTANCE.parseTaskEndTime("", "没有截止字段", now) == 0L);
    }

    @Test
    public void publicationDateIsNotMistakenForDeadline() {
        long now = 1800000000000L;
        assertEquals(0L, DeadlineParser.INSTANCE.parseTaskEndTime(
                "发布于 2026-09-01 10:00", "作业要求：按时完成", now));
    }

    @Test
    public void explicitlyLabeledDeadlineDateIsAccepted() throws Exception {
        long now = date("2026-10-06 12:00");
        long expected = date("2026-10-14 23:59");
        assertEquals(expected, DeadlineParser.INSTANCE.parseTaskEndTime(
                "截止时间：2026-10-14 23:59", "", now));
        assertEquals(expected, DeadlineParser.INSTANCE.parseExplicitDeadlineField(
                "2026-10-14 23:59", now));
    }

    @Test
    public void expiredStatusWithoutActualDeadlineStaysUnknown() {
        long now = 1800000000000L;
        assertEquals(0L, DeadlineParser.INSTANCE.parseTaskEndTime("已截止", "", now));
        assertEquals(0L, DeadlineParser.INSTANCE.parseTaskEndTime("", "已过期", now));
        assertEquals(0L, DeadlineParser.INSTANCE.parseTaskEndTime("作业已结束", "", now));
    }

    @Test
    public void explicitDeadlineRemainsKnownAlongsideExpiredStatus() throws Exception {
        long now = date("2026-10-17 12:00");
        long expected = date("2026-10-14 23:59");
        assertEquals(expected, DeadlineParser.INSTANCE.parseTaskEndTime(
                "截止时间：2026-10-14 23:59 已截止", "", now));
    }

    @Test
    public void genericCourseEndTimeDoesNotHideAnActiveCourse() throws Exception {
        long now = date("2026-10-06 12:00");
        java.util.Map<String, String> course = Collections.singletonMap("endTime", "2026-01-01 00:00");
        assertFalse(CourseStatus.isEnded(now, course));
    }

    @Test
    public void explicitCourseEndDateIsUsedWhenItIsInThePast() throws Exception {
        long now = date("2026-10-06 12:00");
        java.util.Map<String, String> course = Collections.singletonMap("courseEndDate", "2026-10-01 23:59");
        assertTrue(CourseStatus.isEnded(now, course));
    }

    @Test
    public void futureCourseEndDateDoesNotMarkCourseEnded() throws Exception {
        long now = date("2026-10-06 12:00");
        java.util.Map<String, String> course = Collections.singletonMap("courseEndDate", "2026-10-12 23:59");
        assertFalse(CourseStatus.isEnded(now, course));
    }

    @Test
    public void dateScopeExcludesUnknownItemsFromSelectedRangeAndKeepsCompletedItemsInRange() throws Exception {
        long start = date("2026-10-06 00:00");
        long end = date("2026-10-12 23:59");
        assertTrue(HomeworkScope.contains(homework(date("2026-10-08 12:00"), Homework.PENDING), false, start, end));
        assertFalse(HomeworkScope.contains(homework(date("2026-09-30 12:00"), Homework.PENDING), false, start, end));
        assertFalse(HomeworkScope.contains(homework(0L, Homework.PENDING), false, start, end));
        assertFalse(HomeworkScope.contains(homework(0L, Homework.DONE), false, start, end));
        assertTrue(HomeworkScope.contains(homework(date("2026-10-08 12:00"), Homework.DONE), false, start, end));
    }

    private static Homework homework(long endTime, int status) {
        return new Homework("id", "uid", "课程", "作业", endTime, status, "",
                Homework.AUTO, "course", null, false);
    }

    private static long date(String value) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(value).getTime();
    }
}
