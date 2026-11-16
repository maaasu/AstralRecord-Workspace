import { act, fireEvent, screen, within } from '@testing-library/react'

export async function respondToConfirmation(accept: boolean, title = '未保存の編集内容', label = '変更を破棄') {
  const dialog = await screen.findByRole('dialog', { name: title })
  await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: accept ? label : 'キャンセル' })) })
}
