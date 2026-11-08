import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { transactionApi } from '../../api/transactionApi';
import type { TransactionResponse } from '../../types/transaction';
import { CheckCircle2, Clock, XCircle, AlertTriangle, ArrowLeft } from 'lucide-react';

export const TransactionDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const [transaction, setTransaction] = useState<TransactionResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (id) {
      fetchTransaction(id);
    }
  }, [id]);

  const fetchTransaction = async (transactionId: string) => {
    try {
      setLoading(true);
      const data = await transactionApi.getTransaction(transactionId);
      setTransaction(data);
    } catch (err) {
      setError('Could not load transaction details.');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="animate-pulse h-64 bg-border rounded-card max-w-2xl mx-auto"></div>;
  }

  if (error || !transaction) {
    return (
      <div className="text-center py-12">
        <p className="text-error mb-4">{error}</p>
        <Link to="/dashboard" className="text-primary hover:text-primary-dark font-semibold">Return to Dashboard</Link>
      </div>
    );
  }

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'COMPLETED': return <CheckCircle2 className="h-16 w-16 text-green-success" />;
      case 'FAILED': return <XCircle className="h-16 w-16 text-error" />;
      case 'PENDING_VERIFICATION':
      case 'FLAGGED': return <AlertTriangle className="h-16 w-16 text-orange" />;
      default: return <Clock className="h-16 w-16 text-primary" />;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'bg-green-light text-green-success';
      case 'FAILED': return 'bg-red-50 text-error';
      case 'PENDING_VERIFICATION':
      case 'FLAGGED': return 'bg-orange-light text-orange';
      default: return 'bg-blueLt text-primary';
    }
  };

  return (
    <div className="max-w-2xl mx-auto">
      <Link to="/dashboard" className="inline-flex items-center gap-2 text-sm font-semibold text-muted hover:text-navy transition-colors mb-6">
        <ArrowLeft className="h-4 w-4" /> Back to Dashboard
      </Link>

      <div className="card overflow-hidden">
        <div className="p-8 text-center border-b border-border bg-bg">
          <div className="flex justify-center mb-4">
            {getStatusIcon(transaction.status)}
          </div>
          <h2 className="text-4xl font-bold text-navy mb-3">
            Rs. {transaction.amount.toLocaleString('en-PK', { minimumFractionDigits: 2 })}
          </h2>
          <span className={`inline-flex items-center px-3 py-1 rounded-full text-sm font-medium ${getStatusColor(transaction.status)}`}>
            {transaction.status.replace('_', ' ')}
          </span>
        </div>

        <div className="p-8 space-y-6">
          <dl className="grid grid-cols-1 gap-x-4 gap-y-6 sm:grid-cols-2">
            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">Transaction ID</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium font-mono">{transaction.id}</dd>
            </div>
            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">Reference Number</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium font-mono">{transaction.referenceNumber || 'N/A'}</dd>
            </div>
            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">From</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium">
                {transaction.senderName ? `${transaction.senderName} (${transaction.senderAccountNumber})` : transaction.senderAccountNumber}
              </dd>
            </div>
            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">To</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium">
                {transaction.receiverName ? `${transaction.receiverName} (${transaction.receiverAccountNumber})` : transaction.receiverAccountNumber}
              </dd>
            </div>
            
            {transaction.description && (
              <div className="sm:col-span-2">
                <dt className="text-sm font-medium text-muted">Description</dt>
                <dd className="mt-1.5 text-sm text-navy font-medium">{transaction.description}</dd>
              </div>
            )}
            
            {transaction.failureReason && (
              <div className="sm:col-span-2">
                <dt className="text-sm font-medium text-muted">Failure Reason</dt>
                <dd className="mt-1.5 text-sm text-error bg-red-50 p-3.5 rounded-btn border border-red-100">{transaction.failureReason}</dd>
              </div>
            )}

            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">Created At</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium">
                {new Date(transaction.createdAt).toLocaleString()}
              </dd>
            </div>
            
            <div className="sm:col-span-1">
              <dt className="text-sm font-medium text-muted">Completed At</dt>
              <dd className="mt-1.5 text-sm text-navy font-medium">
                {transaction.completedAt ? new Date(transaction.completedAt).toLocaleString() : 'Pending'}
              </dd>
            </div>
          </dl>

          {transaction.status === 'PENDING_VERIFICATION' && (
            <div className="mt-8 pt-6 border-t border-border text-center">
              <p className="text-sm text-body mb-4">This transaction requires an OTP verification to proceed.</p>
              <Link
                to={`/verify/${transaction.id}`}
                className="btn-primary inline-flex items-center gap-2"
              >
                Verify Now
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
