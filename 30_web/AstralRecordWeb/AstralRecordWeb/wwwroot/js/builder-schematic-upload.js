const schematicInput = document.querySelector("[data-max-upload-bytes]");

schematicInput?.addEventListener("change", () => {
    const file = schematicInput.files[0];
    const tooLarge = file && file.size > Number(schematicInput.dataset.maxUploadBytes);
    schematicInput.setCustomValidity(tooLarge ? "1 MiB 以下のファイルを選択してください。" : "");
    if (tooLarge) schematicInput.reportValidity();
});
