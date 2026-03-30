import { useCallback, useRef } from 'react'
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

interface Options {
  editor: Editor | null
  documentId: string
  sessionId: string
  currentVersion: MutableRefObject<number>
  submitOperation: (req: SubmitOperationRequest) => void
}

export function useTiptapCollaboration({
  editor,
  sessionId,
  currentVersion,
  submitOperation,
}: Options) {
  const pendingOps = useRef<Map<string, SubmitOperationRequest>>(new Map())

  // Called by EditorPage on each Tiptap 'transaction' event
  const onTransaction = useCallback(
    (transaction: any) => {
      if (!transaction.docChanged || !editor) return
      for (const step of transaction.steps) {
        const stepJson = step.toJSON()
        const classified = classifyStep(stepJson, transaction.before)
        if (!classified) continue
        const req: SubmitOperationRequest = {
          operationId: uuidv4(),
          clientSessionId: sessionId,
          baseVersion: currentVersion.current,
          ...classified,
        }
        pendingOps.current.set(req.operationId, req)
        submitOperation(req)
      }
    },
    [editor, sessionId, submitOperation],
  )

  // Called by useCollaboration.onOperation when an accepted operation arrives
  const onAcceptedOperation = useCallback(
    (op: AcceptedOperationResponse) => {
      currentVersion.current = op.serverVersion
      if (pendingOps.current.has(op.operationId)) {
        // Own echo — drop it, version is already advanced
        pendingOps.current.delete(op.operationId)
        return
      }
      if (!editor) return
      applyAcceptedOperation(editor, op)
    },
    [editor],
  )

  return { onTransaction, onAcceptedOperation }
}

// ─── Step Classification ──────────────────────────────────────────────────────

function classifyStep(
  stepJson: any,
  doc: any,
): { operationType: OperationType; payload: unknown } | null {
  const blockIndex = (pos: number): number => doc.resolve(pos).index(0)

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
        payload: { path: [blockIndex(from)], offset: from },
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
        payload: { path: [blockIndex(from)], offset: from, text },
      }
    }

    // 5. DELETE_RANGE — empty slice within a single block
    if (from < to && emptySlice) {
      return {
        operationType: 'DELETE_RANGE',
        payload: { path: [blockIndex(from)], from, to },
      }
    }
  }

  // 6. FORMAT_RANGE — addMark step (bold, italic)
  if (stepJson.stepType === 'addMark') {
    return {
      operationType: 'FORMAT_RANGE',
      payload: {
        path: [blockIndex(stepJson.from)],
        from: stepJson.from,
        to: stepJson.to,
        format: { [stepJson.mark.type]: true },
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
    case 'INSERT_TEXT':
      if (payload.offset == null || payload.text == null) break
      editor.chain().insertContentAt(payload.offset, payload.text).run()
      break

    case 'DELETE_RANGE':
      if (payload.from == null || payload.to == null) break
      editor.chain().deleteRange({ from: payload.from, to: payload.to }).run()
      break

    case 'FORMAT_RANGE':
      if (payload.format?.bold) {
        editor
          .chain()
          .setTextSelection({ from: payload.from, to: payload.to })
          .setBold()
          .run()
      }
      if (payload.format?.italic) {
        editor
          .chain()
          .setTextSelection({ from: payload.from, to: payload.to })
          .setItalic()
          .run()
      }
      break

    case 'SPLIT_BLOCK':
      if (payload.offset == null) break
      editor.chain().setTextSelection(payload.offset).splitBlock().run()
      break

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
