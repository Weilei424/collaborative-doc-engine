import { useState } from 'react'
import type { SessionMember } from '../types/collaboration'

interface Props {
  members: SessionMember[]
  currentUserId: string
}

export function SessionPanel({ members, currentUserId }: Props) {
  const [open, setOpen] = useState(false)

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className="text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1"
      >
        <span>{members.length} online</span>
        <span className={`transition-transform ${open ? 'rotate-180' : ''}`}>▾</span>
      </button>
      {open && (
        <div className="absolute right-0 mt-1 w-48 bg-white border rounded shadow-lg z-10">
          {members.map(m => (
            <div key={m.userId} className="flex items-center gap-2 px-3 py-2 text-sm">
              <span className="w-2 h-2 bg-green-400 rounded-full shrink-0" />
              <span className="truncate">
                {m.username}
                {m.userId === currentUserId && ' (you)'}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
