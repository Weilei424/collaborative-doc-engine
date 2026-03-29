import { useState, useEffect, useRef } from 'react'
import { usersApi } from '../api/users'
import type { UserSummary } from '../api/users'

interface Props {
  onSelect: (user: UserSummary) => void
  placeholder?: string
}

export function UserSearchCombobox({ onSelect, placeholder = 'Search users…' }: Props) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<UserSummary[]>([])
  const [open, setOpen] = useState(false)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => {
    clearTimeout(timeoutRef.current)
    if (query.length < 2) { setResults([]); return }
    timeoutRef.current = setTimeout(async () => {
      const users = await usersApi.search(query)
      setResults(users)
      setOpen(true)
    }, 300)
    return () => clearTimeout(timeoutRef.current)
  }, [query])

  function handleSelect(user: UserSummary) {
    onSelect(user)
    setQuery('')
    setResults([])
    setOpen(false)
  }

  return (
    <div className="relative">
      <input
        className="w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={placeholder}
        onFocus={() => results.length > 0 && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {open && results.length > 0 && (
        <ul className="absolute z-10 w-full mt-1 bg-white border rounded shadow-lg max-h-48 overflow-y-auto">
          {results.map(user => (
            <li key={user.userId}
              className="px-3 py-2 text-sm hover:bg-gray-100 cursor-pointer"
              onMouseDown={() => handleSelect(user)}>
              {user.username}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
