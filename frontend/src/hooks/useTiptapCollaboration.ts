import { useCallback, useEffect, useRef } from 'react'
import type { MutableRefObject } from 'react'
import type { Editor } from '@tiptap/react'
import { v4 as uuidv4 } from 'uuid'
import type { AcceptedOperationResponse, OperationType, SubmitOperationRequest } from '../types/collaboration'
// Side-effect imports so TypeScript picks up ChainedCommands augmentations
// from each extension's `declare module '@tiptap/core'` block.
import '@tiptap/extension-bold'
import '@tiptap/extension-italic'
import '@tiptap/extension-heading'
import '@tiptap/extension-paragraph'

interface PendingEntry {
  req: SubmitOperationRequest
  // ProseMirror step + the document immediately before it was applied.
  // Used to invert the optimistic application when the server responds NO_OP.
  steps: Array<{ step: any; beforeDoc: any }>
}

interface Options {
  editor: Editor | null
  documentId: string
  sessionId: string
  currentVersion: MutableRefObject<number>
  submitOperation: (req: SubmitOperationRequest) => void
  token: string | null
}

export function useTiptapCollaboration({
  editor,
  documentId,
  sessionId,
  currentVersion,
  submitOperation,
  token,
}: Options) {
  const pendingOps = useRef<Map<string, PendingEntry>>(new Map())
  // True while applying a remote (or corrective) operation so that onTransaction
  // does not re-classify and re-submit the resulting Tiptap transaction.
  const isApplyingRemote = useRef(false)

  const gapBuffer = useRef<Map<number, AcceptedOperationResponse>>(new Map())
  const fetchingGap = useRef(false)
  const selfRef = useRef<(op: AcceptedOperationResponse) => void>(() => {})

  // Called by EditorPage on each Tiptap 'transaction' event
  const onTransaction = useCallback(
    (transaction: any) => {
      if (isApplyingRemote.current) return
      if (!transaction.docChanged || !editor) return

      let beforeDoc = transaction.before

      for (const step of transaction.steps) {
        const stepJson = step.toJSON()
        const classified = classifyStep(stepJson, beforeDoc)

        if (classified) {
          const req: SubmitOperationRequest = {
            operationId: uuidv4(),
            clientSessionId: sessionId,
            baseVersion: currentVersion.current,
            ...classified,
          }
          pendingOps.current.set(req.operationId, { req, steps: [{ step, beforeDoc }] })
          submitOperation(req)
        }

        // Advance beforeDoc for the next step in this transaction
        const result = step.apply(beforeDoc)
        if (result.failed || !result.doc) break
        beforeDoc = result.doc
      }
    },
    [editor, sessionId, submitOperation],
  )

  // Called by useCollaboration.onOperation when an accepted operation arrives
  const onAcceptedOperation = useCallback(
    (op: AcceptedOperationResponse) => {
      // Duplicate delivery — already applied via live topic before catchup queue arrived
      if (op.serverVersion <= currentVersion.current) {
        return
      }

      // Gap detection: op arrived out of order — a Redis message was lost
      if (op.serverVersion > currentVersion.current + 1) {
        gapBuffer.current.set(op.serverVersion, op)
        if (!fetchingGap.current) {
          fetchingGap.current = true
          fetchGapFill(documentId, currentVersion.current, token)
            .then(ops => {
              for (const o of ops) selfRef.current(o)
              // Drain any buffered ops that are now contiguous
              let next = currentVersion.current + 1
              while (gapBuffer.current.has(next)) {
                selfRef.current(gapBuffer.current.get(next)!)
                gapBuffer.current.delete(next)
                next++
              }
              if (gapBuffer.current.size > 0) {
                console.warn('[collab] Gap fill left', gapBuffer.current.size, 'buffered ops unresolved — partial convergence')
              }
            })
            .catch((err) => {
              console.warn('[collab] Gap fill failed — client may be out of sync:', err)
            })
            .finally(() => {
              fetchingGap.current = false
            })
        }
        return
      }

      // --- existing apply logic below, unchanged ---
      currentVersion.current = op.serverVersion
      const pending = pendingOps.current.get(op.operationId)
      if (pending) {
        pendingOps.current.delete(op.operationId)
        const needsReconcile =
          op.operationType === 'NO_OP' ||
          JSON.stringify(op.transformedPayload) !== JSON.stringify(pending.req.payload)

        if (needsReconcile && editor) {
          isApplyingRemote.current = true
          try {
            const view = editor.view
            let tr = view.state.tr
            for (let i = pending.steps.length - 1; i >= 0; i--) {
              const { step, beforeDoc } = pending.steps[i]
              tr = tr.step(step.invert(beforeDoc))
            }
            view.dispatch(tr)

            if (op.operationType !== 'NO_OP') {
              applyAcceptedOperation(editor, op)
            }
          } catch (e) {
            console.warn('[collab] Failed to reconcile transformed echo — client may diverge:', e)
          } finally {
            isApplyingRemote.current = false
          }
        }
        return
      }
      if (!editor) return
      isApplyingRemote.current = true
      try {
        applyAcceptedOperation(editor, op)
      } finally {
        isApplyingRemote.current = false
      }
    },
    [editor, documentId, token],
  )

  useEffect(() => {
    selfRef.current = onAcceptedOperation
  }, [onAcceptedOperation])

  return { onTransaction, onAcceptedOperation }
}

// ─── Gap Fill ────────────────────────────────────────────────────────────────

async function fetchGapFill(
  documentId: string,
  sinceVersion: number,
  token: string | null,
): Promise<AcceptedOperationResponse[]> {
  const result: AcceptedOperationResponse[] = []
  let currentSince = sinceVersion
  let hasMore = true
  while (hasMore) {
    const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {}
    let res: Response
    try {
      res = await fetch(
        `/api/documents/${documentId}/operations?sinceVersion=${currentSince}&limit=500`,
        { headers },
      )
    } catch {
      break
    }
    if (!res.ok) break
    const page = await res.json()
    const ops = page.operations as AcceptedOperationResponse[]
    result.push(...ops)
    if (ops.length > 0) currentSince = ops[ops.length - 1].serverVersion
    hasMore = page.hasMore as boolean
  }
  return result
}

// ─── Step Classification ──────────────────────────────────────────────────────

function classifyStep(
  stepJson: any,
  doc: any,
): { operationType: OperationType; payload: unknown } | null {
  const blockIndex = (pos: number): number => doc.resolve(pos).index(0)
  // Returns the block-relative character offset for a ProseMirror document
  // position. The backend operates on block-relative offsets, not absolute
  // ProseMirror positions.
  const blockOffset = (pos: number): number => pos - doc.resolve(pos).start(1)

  if (stepJson.stepType === 'replace') {
    const from: number = stepJson.from
    const to: number = stepJson.to
    const slice = stepJson.slice
    const openStart: number = slice?.openStart ?? 0
    const openEnd: number = slice?.openEnd ?? 0
    const hasContent = Array.isArray(slice?.content) && slice.content.length > 0
    const emptySlice = !hasContent

    // 1. SPLIT_BLOCK — Enter key: openStart > 0 && openEnd > 0 is the
    //    reliable ProseMirror signature for block splits
    if (openStart > 0 && openEnd > 0) {
      return {
        operationType: 'SPLIT_BLOCK',
        payload: { path: [blockIndex(from)], offset: blockOffset(from) },
      }
    }

    // 2. MERGE_BLOCK — empty slice, from < to, positions in different blocks
    if (emptySlice && from < to && doc.resolve(from).index(0) !== doc.resolve(to).index(0)) {
      return {
        operationType: 'MERGE_BLOCK',
        payload: { path: [blockIndex(from)] },
      }
    }

    // 3. SET_BLOCK_TYPE — range replacement with a single block node
    //    (Tiptap's setHeading / setParagraph commands produce this)
    if (from < to && hasContent && slice.content.length === 1) {
      const nodeType: string = slice.content[0].type
      if (nodeType === 'paragraph' || nodeType === 'heading') {
        const blockType =
          nodeType === 'paragraph'
            ? 'paragraph'
            : `heading${(slice.content[0].attrs?.level ?? 1) as number}`
        return {
          operationType: 'SET_BLOCK_TYPE',
          payload: { path: [blockIndex(from)], blockType },
        }
      }
    }

    // 4. INSERT_TEXT — zero-width insertion with text content
    if (from === to && hasContent) {
      const text = extractText(slice.content)
      if (!text) return null
      return {
        operationType: 'INSERT_TEXT',
        payload: { path: [blockIndex(from)], offset: blockOffset(from), text },
      }
    }

    // 5. DELETE_RANGE — empty slice within a single block
    if (from < to && emptySlice) {
      return {
        operationType: 'DELETE_RANGE',
        payload: { path: [blockIndex(from)], offset: blockOffset(from), length: to - from },
      }
    }
  }

  // 6. FORMAT_RANGE — addMark step (bold, italic)
  if (stepJson.stepType === 'addMark') {
    const from: number = stepJson.from
    return {
      operationType: 'FORMAT_RANGE',
      payload: {
        path: [blockIndex(from)],
        offset: blockOffset(from),
        length: stepJson.to - from,
        attributes: { [stepJson.mark.type]: true },
      },
    }
  }

  // removeMark steps (un-bold, un-italic) are not classified — they apply locally
  // but are not transmitted to the backend. This is a documented MVP gap.

  // 7. Unclassified — local-only, documented MVP gap
  return null
}

// ─── Apply Remote Operations ─────────────────────────────────────────────────

function applyAcceptedOperation(editor: Editor, op: AcceptedOperationResponse): void {
  const payload = op.transformedPayload as any
  if (!payload) return

  switch (op.operationType) {
    case 'INSERT_TEXT': {
      if (payload.offset == null || payload.text == null) break
      const pos = pmPos(editor.state.doc, payload.path?.[0] ?? 0, payload.offset)
      editor.chain().insertContentAt(pos, payload.text).run()
      break
    }

    case 'DELETE_RANGE': {
      if (payload.offset == null || payload.length == null) break
      const from = pmPos(editor.state.doc, payload.path?.[0] ?? 0, payload.offset)
      editor.chain().deleteRange({ from, to: from + payload.length }).run()
      break
    }

    case 'FORMAT_RANGE': {
      if (payload.offset == null || payload.length == null) break
      const from = pmPos(editor.state.doc, payload.path?.[0] ?? 0, payload.offset)
      const to = from + payload.length
      const attrs = (payload.attributes ?? {}) as Record<string, boolean>
      if (attrs.bold) editor.chain().setTextSelection({ from, to }).setBold().run()
      if (attrs.italic) editor.chain().setTextSelection({ from, to }).setItalic().run()
      break
    }

    case 'SPLIT_BLOCK': {
      if (payload.offset == null) break
      const pos = pmPos(editor.state.doc, payload.path?.[0] ?? 0, payload.offset)
      editor.chain().setTextSelection(pos).splitBlock().run()
      break
    }

    case 'MERGE_BLOCK': {
      const blockIdx: number = payload.path?.[0] ?? 0
      const doc = editor.state.doc
      if (blockIdx < doc.childCount) {
        // Compute the last position inside block at blockIdx
        let startPos = 1
        for (let i = 0; i < blockIdx; i++) startPos += doc.child(i).nodeSize
        const endOfBlock = startPos + doc.child(blockIdx).nodeSize - 1
        editor.chain().setTextSelection(endOfBlock).joinForward().run()
      }
      break
    }

    case 'SET_BLOCK_TYPE': {
      const blockIdx: number = payload.path?.[0] ?? 0
      const doc = editor.state.doc
      if (blockIdx < doc.childCount) {
        // pos 1 = first character inside the first block; add nodeSize per block
        let startPos = 1
        for (let i = 0; i < blockIdx; i++) startPos += doc.child(i).nodeSize
        if (payload.blockType === 'paragraph') {
          editor.chain().setTextSelection(startPos).setParagraph().run()
        } else if (
          typeof payload.blockType === 'string' &&
          payload.blockType.startsWith('heading')
        ) {
          const level = parseInt(payload.blockType.replace('heading', ''), 10) as 1 | 2 | 3
          editor.chain().setTextSelection(startPos).setHeading({ level }).run()
        }
      }
      break
    }

    case 'NO_OP':
      break
  }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

// Converts a block index + block-relative character offset to an absolute
// ProseMirror document position. The block at index 0 has its content starting
// at position 1 (after its opening token). Each block's opening and closing
// tokens each consume one position unit.
function pmPos(doc: any, blockIdx: number, offset: number): number {
  let pos = 0
  for (let i = 0; i < blockIdx; i++) {
    pos += doc.child(i).nodeSize
  }
  return pos + 1 + offset // +1 to pass the block's opening token
}

function extractText(content: any[]): string {
  return content
    .flatMap((node: any) =>
      node.type === 'text'
        ? [node.text ?? '']
        : node.content
          ? [extractText(node.content)]
          : [],
    )
    .join('')
}
