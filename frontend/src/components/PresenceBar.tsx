import type { SessionMember } from '../types/collaboration'

const COLORS = [
  'bg-red-400',
  'bg-blue-400',
  'bg-green-400',
  'bg-yellow-400',
  'bg-purple-400',
  'bg-pink-400',
  'bg-indigo-400',
  'bg-teal-400',
]

interface Props {
  members: SessionMember[]
  currentUserId: string
}

export function PresenceBar({ members, currentUserId }: Props) {
  return (
    <div className="flex items-center gap-1">
      {members.map((m, i) => (
        <div
          key={m.userId}
          title={m.username + (m.userId === currentUserId ? ' (you)' : '')}
          className={`w-7 h-7 rounded-full flex items-center justify-center text-white text-xs font-bold ${COLORS[i % COLORS.length]}${m.userId === currentUserId ? ' ring-2 ring-offset-1 ring-gray-400' : ''}`}
        >
          {(m.username[0] ?? '?').toUpperCase()}
        </div>
      ))}
    </div>
  )
}
