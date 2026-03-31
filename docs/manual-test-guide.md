# Manual Test Guide

## Prerequisites

- `docker compose up` — all five containers running and healthy
- Browser open at `http://localhost:3000`
- A second browser window or incognito tab available for multi-user tests

> **Demo accounts available.** When running with the default `dev` profile, five accounts are pre-seeded: `alice`, `bob`, `carol`, `dave`, `eve` — all with password `demo1234`. You can log in as any of them instead of registering. Tests that register `alice` directly will fail if the seeder has already run; use a different username (e.g. `alice2`) or log in with the pre-seeded credentials.

---

## Test 1 — Registration

1. Go to `http://localhost:3000`
2. Click **Register**
3. Fill in: username `alice`, email `alice@test.com`, password `password123`
4. Submit

**Expected:** redirected to the dashboard, "Mine" tab visible, document list empty.

---

## Test 2 — Create and open a document

1. Click **New document**
2. Enter title `Hello World` and confirm

**Expected:** a document card appears in the "Mine" tab immediately.

3. Click the card

**Expected:**
- Editor opens
- Green dot visible in the header
- Toast notification `Connected` appears briefly in the bottom-right corner

---

## Test 3 — Edit and persist

1. In the editor, type `This is my first document`
2. Press the browser back button to return to the dashboard
3. Click the `Hello World` card again

**Expected:** the text `This is my first document` is still present.

---

## Test 4 — Delete a document (optimistic)

1. On the dashboard, click **Delete** on the `Hello World` card
2. Confirm the prompt

**Expected:** the card disappears immediately without a page reload.

---

## Test 5 — Share a document and collaborate

Open an incognito window alongside the main window.

1. **Main window (alice):** register alice if not already done
2. **Incognito (bob):** go to `http://localhost:3000/register`, register as `bob` / `bob@test.com` / `password123`
3. **Alice:** create a document called `Shared Doc`, open its editor, then click **Settings** in the header
4. **Alice (Settings):** set visibility to **Shared**, search for `bob`, add with **Write** permission, save
5. **Bob:** switch to the **Shared with me** tab on the dashboard

**Expected:** `Shared Doc` appears in bob's list.

6. Both alice and bob open `Shared Doc`

**Expected:** both see the other's username/avatar in the presence bar.

7. **Alice:** type `Hello from Alice`

**Expected:** bob sees the text appear in real time.

8. **Bob:** type ` and Bob`

**Expected:** alice sees the combined text.

---

## Test 6 — Access revocation

Continuing from Test 5, with both alice and bob in the editor for `Shared Doc`.

1. **Alice:** open Settings, find bob in the collaborators list, remove him
2. Watch bob's browser

**Expected:** bob's editor shows an access-revoked overlay and becomes non-editable immediately.

---

## Test 7 — Disconnect and reconnect toast

1. Open any document in the editor (green dot visible)
2. In a terminal, stop the backend: `docker compose stop backend`

**Expected:**
- Dot turns grey
- Toast `Disconnected — reconnecting…` appears

3. Restart: `docker compose start backend`

**Expected:**
- Dot turns green
- Toast `Connected` appears

---

## Test 8 — Public document visibility

1. **Alice:** create a document `Public Doc`, open Settings, set visibility to **Public**, save
2. **Alice:** log out (Sign out button in the header)
3. Register a new user `carol` / `carol@test.com` / `password123`
4. On carol's dashboard, click the **Public** tab

**Expected:** `Public Doc` is listed and can be opened for reading.

---

## Test 9 — Search

1. As any user who owns several documents, type part of a title in the search box

**Expected:** the document list filters in real time (300 ms debounce) to show only matching titles.

2. Clear the search box

**Expected:** the full list returns.

---

## Test 10 — Pagination

Requires more than 20 documents in a single scope tab. Create 21+ documents.

**Expected:**
- Pagination controls appear below the list
- **Previous** / **Next** buttons navigate between pages
- **Previous** is disabled on page 1, **Next** is disabled on the last page

---

## Test 11 — Mobile layout

Resize the browser window to below 768 px width (or use DevTools device emulation).

**Expected on the dashboard:**
- Username hidden
- "New document" button shows `+` instead of the full label
- "Sign out" button hidden

**Expected in the editor:**
- Header does not clip — it scrolls horizontally if needed
