import { http } from '@/utils/request'

export type TicketType = 'RESOURCE' | 'FAULT' | 'SPECIAL_CONFIG' | 'PERMISSION' | 'IMAGE'

export interface Ticket {
  id: number
  ticketNo?: string
  title: string
  type: TicketType
  content: string
  submitterId: number
  submitterName: string
  groupName?: string
  status: string
  reply?: string
  replierName?: string
  repliedAt?: string
  createdAt: string
}

export const ticketApi = {
  list: () => http.get<Ticket[]>('/tickets'),
  get: (id: number) => http.get<Ticket>(`/tickets/${id}`),
  create: (data: { title: string; type: TicketType; content: string }) => http.post<Ticket>('/tickets', data),
  close: (id: number, reply: string) => http.post<Ticket>(`/tickets/${id}/close`, { reply }),
  /** 提交人撤销工单（platform-refinements #6） */
  cancel: (id: number) => http.delete(`/tickets/${id}`),
  history: (id: number) => http.get<Array<{ action: string; operatorName: string; content: string; createdAt: string }>>(`/tickets/${id}/history`),
}
