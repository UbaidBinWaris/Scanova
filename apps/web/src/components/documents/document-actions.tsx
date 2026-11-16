'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import {
  MAX_TAG_LENGTH,
  MAX_TAGS,
  MAX_TITLE_LENGTH,
  type Document,
} from '@scanova/contracts/documents';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useState, useTransition, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Button, buttonClasses } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Alert } from '@/components/ui/feedback';
import { Field, fieldAria, Input } from '@/components/ui/field';
import { DownloadIcon, PencilIcon, TagIcon, TrashIcon, XIcon } from '@/components/ui/icons';
import { useToast } from '@/components/ui/toast';
import { normaliseTags } from '@/lib/validation';
import { deleteDocument, renameDocument, setDocumentTags } from '@/server/actions/documents';

type Open = 'rename' | 'tags' | 'delete' | null;

export function DocumentActions({ document }: { document: Document }): ReactNode {
  const [open, setOpen] = useState<Open>(null);
  const close = (): void => {
    setOpen(null);
  };
  return (
    <div className="flex flex-wrap gap-2">
      <a
        className={buttonClasses('primary')}
        href={`/app/documents/${document.id}/file?download=1`}
        download
      >
        <DownloadIcon width={18} height={18} />
        Download PDF
      </a>
      {document.hasSearchablePdf ? (
        <a
          className={buttonClasses('secondary')}
          href={`/app/documents/${document.id}/file?variant=searchable&download=1`}
          download
        >
          Searchable PDF
        </a>
      ) : null}
      <Button variant="secondary" onClick={() => setOpen('rename')}>
        <PencilIcon width={18} height={18} />
        Rename
      </Button>
      <Button variant="secondary" onClick={() => setOpen('tags')}>
        <TagIcon width={18} height={18} />
        Tags
      </Button>
      <Button variant="danger" onClick={() => setOpen('delete')}>
        <TrashIcon width={18} height={18} />
        Delete
      </Button>
      {/* Mounted only while open, so each opening starts from the current document. */}
      {open === 'rename' ? <RenameDialog document={document} open onClose={close} /> : null}
      {open === 'tags' ? <TagsDialog document={document} open onClose={close} /> : null}
      {open === 'delete' ? <DeleteDialog document={document} open onClose={close} /> : null}
    </div>
  );
}

const RenameFormSchema = z.object({
  title: z.string().trim().min(1, 'Enter a title.').max(MAX_TITLE_LENGTH),
});

function RenameDialog({
  document,
  open,
  onClose,
}: {
  document: Document;
  open: boolean;
  onClose: () => void;
}): ReactNode {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { notify } = useToast();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<z.input<typeof RenameFormSchema>, unknown, z.output<typeof RenameFormSchema>>({
    resolver: zodResolver(RenameFormSchema),
    defaultValues: { title: document.title },
  });
  const onSubmit = handleSubmit(async ({ title }) => {
    const result = await renameDocument({ id: document.id, title });
    if (!result.ok) {
      setError('title', { message: result.fieldErrors?.title ?? result.message });
      return;
    }
    await queryClient.invalidateQueries({ queryKey: ['documents'] });
    notify('Renamed');
    onClose();
    router.refresh();
  });
  return (
    <Dialog open={open} onClose={onClose} title="Rename document">
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <Field id="rename-title" label="Title" error={errors.title?.message}>
          <Input
            dir="auto"
            maxLength={MAX_TITLE_LENGTH}
            {...fieldAria('rename-title', errors.title?.message)}
            {...register('title')}
          />
        </Field>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={isSubmitting}>
            Save title
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function TagsDialog({
  document,
  open,
  onClose,
}: {
  document: Document;
  open: boolean;
  onClose: () => void;
}): ReactNode {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { notify } = useToast();
  const [tags, setTags] = useState<string[]>(document.tags);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const add = (): void => {
    const next = normaliseTags([...tags, ...draft.split(',')]);
    if (draft.trim().length > MAX_TAG_LENGTH) {
      setError(`A tag can have at most ${MAX_TAG_LENGTH} characters.`);
      return;
    }
    setTags(next);
    setDraft('');
    setError(null);
  };

  const save = (): void => {
    const pendingDraft = draft.trim() === '' ? tags : normaliseTags([...tags, ...draft.split(',')]);
    startTransition(async () => {
      const result = await setDocumentTags({ id: document.id, tags: pendingDraft });
      if (!result.ok) {
        setError(result.message);
        return;
      }
      await queryClient.invalidateQueries({ queryKey: ['documents'] });
      notify('Tags saved');
      onClose();
      router.refresh();
    });
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Tags"
      description={`Up to ${MAX_TAGS} tags. Tags are searchable, like titles.`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button pending={pending} onClick={save}>
            Save tags
          </Button>
        </>
      }
    >
      {error !== null ? <Alert>{error}</Alert> : null}
      {tags.length > 0 ? (
        <ul className="flex flex-wrap gap-2" aria-label="Current tags">
          {tags.map((tag) => (
            <li key={tag}>
              <span className="inline-flex items-center gap-1 rounded-full bg-primary-container py-1 pl-3 pr-1 text-sm text-primary-on-container">
                <span dir="auto">{tag}</span>
                <button
                  type="button"
                  aria-label={`Remove tag ${tag}`}
                  className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-primary/20"
                  onClick={() => {
                    setTags(tags.filter((candidate) => candidate !== tag));
                  }}
                >
                  <XIcon width={14} height={14} />
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">No tags yet.</p>
      )}
      <div className="flex flex-col gap-1.5">
        <label htmlFor="new-tag" className="text-sm font-medium">
          Add a tag
        </label>
        <div className="flex gap-2">
          <Input
            id="new-tag"
            dir="auto"
            value={draft}
            maxLength={MAX_TAG_LENGTH * 4}
            disabled={tags.length >= MAX_TAGS}
            placeholder="e.g. tax, 2026"
            onChange={(event) => {
              setDraft(event.target.value);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ',') {
                event.preventDefault();
                add();
              }
            }}
          />
          <Button
            variant="secondary"
            onClick={add}
            disabled={draft.trim() === '' || tags.length >= MAX_TAGS}
          >
            Add tag
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

function DeleteDialog({
  document,
  open,
  onClose,
}: {
  document: Document;
  open: boolean;
  onClose: () => void;
}): ReactNode {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { notify } = useToast();
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const confirm = (): void => {
    startTransition(async () => {
      const result = await deleteDocument(document.id);
      if (!result.ok) {
        setError(result.message);
        return;
      }
      await queryClient.invalidateQueries({ queryKey: ['documents'] });
      notify('Document deleted');
      router.replace('/app');
    });
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Delete this document?"
      description={
        <>
          <span dir="auto" className="font-medium text-foreground">
            “{document.title}”
          </span>{' '}
          is removed from your account and from every phone that syncs with it. The server deletes
          it for good after its trash period; it cannot be restored from the web.
        </>
      }
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Keep it
          </Button>
          <Button variant="destructive" pending={pending} onClick={confirm}>
            Delete document
          </Button>
        </>
      }
    >
      {error !== null ? <Alert>{error}</Alert> : null}
    </Dialog>
  );
}
