import { useId, type InputHTMLAttributes } from 'react'

interface SuggestionInputProps extends InputHTMLAttributes<HTMLInputElement> {
  suggestions?: readonly string[]
}

export function SuggestionInput({ suggestions = [], ...props }: SuggestionInputProps) {
  const listId = `suggestions-${useId().replaceAll(':', '')}`

  return (
    <>
      <input {...props} list={suggestions.length ? listId : undefined} />
      {suggestions.length > 0 && (
        <datalist id={listId}>
          {suggestions.map((suggestion) => <option key={suggestion} value={suggestion} />)}
        </datalist>
      )}
    </>
  )
}
