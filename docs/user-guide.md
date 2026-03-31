# User Guide

## Accessing the app

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080/api |

---

## Getting started

1. Open `http://localhost:3000` and click **Register**
2. Enter a username, email, and password
3. You will be taken to your document dashboard

---

## Dashboard

The dashboard shows all documents you have access to, organised into three tabs:

| Tab | Shows |
|---|---|
| Mine | Documents you own |
| Shared with me | Documents others have explicitly shared with you |
| Public | All publicly visible documents |

- Use the **search box** to filter by title within the active tab
- Click **New document** (or **+** on mobile) to create a document
- Click any document card to open it in the editor
- Owners see a **Settings** link and a **Delete** button on each card
- Deleting a document removes its card immediately — no page reload required

---

## Editor

The editor opens a document for real-time collaborative editing.

- The **coloured dot** in the header shows your connection status
  - Green — connected and receiving updates
  - Grey — disconnected, reconnection in progress
- A **toast notification** appears whenever the connection state changes
- Start typing — changes are sent to the server as typed operations
- The **presence bar** in the header shows avatars for everyone currently in the document
- Click the **Sessions** panel to see a full list of active collaborators

---

## Document settings (owners only)

Open Settings from the editor header or from the document card.

### Title and visibility

| Visibility | Who can access |
|---|---|
| Private | Owner only |
| Shared | Owner + explicitly invited collaborators |
| Public | Anyone with the link |

### Collaborators

- Search for users by username using the search box
- Add them with **Read** (view only) or **Write** (edit) permission
- Remove a collaborator at any time — they will see an access-revoked overlay immediately if they are currently in the editor
- Transfer ownership to another user from the collaborators list

---

## Toast notifications

Short notifications appear in the bottom-right corner of the screen:

| Colour | Meaning |
|---|---|
| Green | Success (connected, saved) |
| Red | Error (disconnected, failed to delete) |
| Dark | Informational |

Toasts dismiss automatically after 4 seconds.
