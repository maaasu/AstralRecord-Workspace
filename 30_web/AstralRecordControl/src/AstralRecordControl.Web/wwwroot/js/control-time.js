(() => {
    "use strict";
    const formatter = new Intl.DateTimeFormat("ja-JP-u-ca-gregory-nu-latn", {
        timeZone: "Asia/Tokyo", year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
    });
    window.ControlTime = {
        format(value) {
            if (value === null || value === undefined || value === "") return "—";
            // API timestamps carry an offset. Reject ambiguous browser-local strings.
            if (typeof value === "string" && !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return "—";
            if (typeof value !== "string" && typeof value !== "number" && !(value instanceof Date)) return "—";
            const date = new Date(value);
            if (!Number.isFinite(date.getTime())) return "—";
            const parts = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
            return `${parts.year}/${parts.month}/${parts.day} ${parts.hour}:${parts.minute}:${parts.second} JST`;
        }
    };
})();
