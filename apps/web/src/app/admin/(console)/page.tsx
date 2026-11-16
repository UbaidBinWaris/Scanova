import { redirect } from 'next/navigation';

export default function ConsoleHome(): never {
  redirect('/admin/users');
}
