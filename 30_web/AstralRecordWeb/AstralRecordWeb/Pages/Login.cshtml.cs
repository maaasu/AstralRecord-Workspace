using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages;

public class LoginModel : PageModel
{
    private static readonly string[] JapaneseWeekdays = ["日", "月", "火", "水", "木", "金", "土"];

    public string DisplayMonthText { get; private set; } = string.Empty;

    public IReadOnlyList<string> WeekdayLabels => JapaneseWeekdays;

    public IReadOnlyList<LoginCalendarCell?> CalendarCells { get; private set; } = [];

    public void OnGet()
    {
        var today = DateOnly.FromDateTime(DateTime.Today);
        var firstDay = new DateOnly(today.Year, today.Month, 1);
        var daysInMonth = DateTime.DaysInMonth(today.Year, today.Month);
        var cells = new List<LoginCalendarCell?>();

        for (var i = 0; i < (int)firstDay.DayOfWeek; i++)
        {
            cells.Add(null);
        }

        for (var day = 1; day <= daysInMonth; day++)
        {
            var date = new DateOnly(today.Year, today.Month, day);
            cells.Add(new LoginCalendarCell(
                day,
                JapaneseWeekdays[(int)date.DayOfWeek],
                date.DayOfWeek is DayOfWeek.Saturday or DayOfWeek.Sunday
            ));
        }

        DisplayMonthText = $"{today.Year}年{today.Month}月";
        CalendarCells = cells;
    }

    public sealed record LoginCalendarCell(int Day, string WeekdayLabel, bool IsHoliday);
}
