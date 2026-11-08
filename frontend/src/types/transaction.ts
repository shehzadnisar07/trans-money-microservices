export interface TransferRequest {
  senderAccountNumber: string;
  receiverAccountNumber: string;
  amount: number;
  description?: string;
}

export interface TransactionResponse {
  id: string;
  senderAccountNumber: string;
  senderName?: string;
  receiverAccountNumber: string;
  receiverName?: string;
  amount: number;
  type: string;
  status: string;
  description?: string;
  failureReason?: string;
  referenceNumber: string;
  createdAt: string;
  completedAt?: string;
}
