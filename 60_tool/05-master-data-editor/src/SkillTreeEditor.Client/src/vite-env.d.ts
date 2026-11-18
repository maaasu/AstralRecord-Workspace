/// <reference types="vite/client" />

interface Window {
  astralEditorHasUnsavedChanges?: () => boolean
  astralEditorBack?: () => boolean
  astralMobileSessionExpired?: boolean
}
